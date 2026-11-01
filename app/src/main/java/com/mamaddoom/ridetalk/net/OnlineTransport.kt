package com.mamaddoom.ridetalk.net

import android.os.Process
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Online mode: every rider connects to the same free public relay server
 * (an MQTT broker, over TLS) and joins one channel named after the group
 * code. All packets are sealed end-to-end with AES-GCM under a key derived
 * from the code before they leave the phone, so the relay only forwards
 * bytes it cannot read, and riders without the code can neither find the
 * channel nor understand it. Online mode therefore requires a group code.
 *
 * Server choice: automatic tries the servers best-first (the same order on
 * every phone, so a group meets on the same one) and falls back to the next
 * when one fails; or the user pins one server by hand.
 */
class OnlineTransport(
    private val myId: Int,
    private val listener: Transport.Listener,
    /** Pinned server id, or null for automatic. Read on every (re)connect. */
    private val pinned: () -> String?,
    /** Servers in automatic order, with any logins filled in. Read on every (re)connect. */
    private val servers: () -> List<RelayServers.Server>,
    /** Privacy mode: constant-rate, same-size, fully encrypted traffic. Read on every (re)connect. */
    private val privacy: () -> Boolean,
    /** Hide my IP: reach the relay through this SOCKS proxy (e.g. Tor via Orbot), or null. */
    private val proxy: () -> java.net.Proxy?,
    private val onStatus: () -> Unit,
) : Link {

    enum class Status { NO_CODE, CONNECTING, CONNECTED, OFFLINE }

    @Volatile var status = Status.CONNECTING
        private set
    @Volatile var server: RelayServers.Server? = null
        private set
    /** Privacy mode and IP hiding as used by the current connection. */
    @Volatile var privacyActive = false
        private set
    @Volatile var hidingIp = false
        private set

    /** Round trip to the relay (ms), from MQTT ping; -1 until measured. */
    @Volatile var rttMs = -1
        private set

    @Volatile override var crypto: PacketCrypto? = null
        set(v) {
            field = v
            conn?.close() // the channel follows the code: reconnect
        }
    override var peerAddresses: List<InetSocketAddress> = emptyList()
    override val boundToWifi = false
    override val wifiRssi: Int? = null
    override val wifiMhz: Int? = null

    @Volatile private var running = false
    @Volatile private var conn: MqttConnection? = null
    @Volatile private var topic = ""
    private var thread: Thread? = null
    private val queue = LinkedBlockingDeque<ByteArray>()
    private var byeSeq = 0

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "rt-online").also { it.start() }
    }

    override fun stop() {
        if (!running) return
        val buf = ByteBuffer.allocate(Protocol.HEADER)
        val len = Protocol.encodeBye(buf, myId, byeSeq++)
        sealed(buf.array(), len)?.let { pkt ->
            try {
                conn?.publish(topic, pkt)
            } catch (_: Exception) {
            }
        }
        running = false
        conn?.close()
        thread?.join(2000)
        thread = null
        queue.clear()
    }

    override fun sendToPeers(data: ByteArray, len: Int) {
        if (status != Status.CONNECTED) return
        val pkt = sealed(data, len) ?: return
        queue.offerLast(pkt)
        // A stalled connection must not turn into seconds of delay: keep only the newest.
        while (queue.size > MAX_QUEUE) queue.pollFirst()
    }

    override fun sendBeacon(data: ByteArray, len: Int) = sendToPeers(data, len)

    private fun sealed(data: ByteArray, len: Int): ByteArray? {
        val c = crypto ?: return null
        val out = ByteArray(len + PacketCrypto.OVERHEAD)
        val n = c.seal(data, len, out)
        return if (n == out.size) out else out.copyOf(n)
    }

    // ---------------------------------------------------------------- connection loop

    private fun run() {
        var backoff = 1000L
        while (running) {
            val c = crypto
            if (c == null) {
                setStatus(Status.NO_CODE)
                sleep(500)
                continue
            }
            val all = servers()
            val order = pinned()?.let { id -> all.filter { it.id == id } }?.ifEmpty { null } ?: all
            var connected = false
            for (s in order) {
                if (!running || crypto !== c) break
                setStatus(Status.CONNECTING, s)
                val m = MqttConnection(s.host, s.port, s.user, s.password, s.tls, proxy())
                try {
                    m.connect("hm-" + Random.nextLong().toULong().toString(16), CONNECT_TIMEOUT_MS, KEEPALIVE_S)
                } catch (e: Exception) {
                    Log.w(TAG, "${s.host}: ${e.message}")
                    continue
                }
                connected = true
                backoff = 1000L
                session(m, s, c)
                break // after a session ends, start again from the best server
            }
            if (!connected && running) {
                setStatus(Status.OFFLINE)
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(10_000L)
            }
        }
        setStatus(Status.OFFLINE)
    }

    /** One connected period: subscribe, then read until the connection drops. */
    private fun session(m: MqttConnection, s: RelayServers.Server, c: PacketCrypto) {
        topic = "hm1/" + c.channelId
        queue.clear()
        try {
            m.subscribe(topic)
        } catch (_: Exception) {
            m.close()
            return
        }
        conn = m
        rttMs = -1
        privacyActive = privacy()
        hidingIp = proxy() != null
        setStatus(Status.CONNECTED, s)
        val writer = if (privacyActive) {
            Thread({ coverLoop(m, c) }, "rt-online-tx")
        } else {
            Thread({ writeLoop(m) }, "rt-online-tx")
        }
        writer.start()
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(Protocol.MAX_PACKET + PacketCrypto.OVERHEAD + 64)
        val scratch = ByteArray(buf.size)
        val bb = ByteBuffer.wrap(buf)
        val from = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), 0)
        try {
            while (running && crypto === c) {
                val p = m.read()
                when (p.type) {
                    MqttCodec.PUBLISH -> {
                        val payload = MqttCodec.parsePublish(p)?.second ?: continue
                        if (payload.isNotEmpty() && payload[0] == PrivacyFrames.MAGIC) {
                            // Privacy-mode message (from any rider, whatever our own setting).
                            val plain = c.openBlob(payload, 1, payload.size - 1) ?: continue
                            for (inner in PrivacyFrames.unpack(plain)) deliver(inner, c, buf, scratch, bb, from)
                        } else {
                            deliver(payload, c, buf, scratch, bb, from)
                        }
                    }
                    MqttCodec.PINGRESP -> if (pingSentAt > 0) {
                        rttMs = (System.currentTimeMillis() - pingSentAt).toInt()
                        pingSentAt = 0
                        onStatus()
                    }
                }
            }
        } catch (_: Exception) {
            // connection dropped
        } finally {
            conn = null
            m.close()
            writer.interrupt()
            writer.join(1000)
            if (running) setStatus(Status.CONNECTING, s)
        }
    }

    /** Opens one sealed packet and hands it to the engine. */
    private fun deliver(
        packet: ByteArray,
        c: PacketCrypto,
        buf: ByteArray,
        scratch: ByteArray,
        bb: ByteBuffer,
        from: InetSocketAddress,
    ) {
        if (packet.size > buf.size) return
        System.arraycopy(packet, 0, buf, 0, packet.size)
        val n = c.open(buf, packet.size, scratch)
        if (n < 0) return // not sealed with our code
        bb.clear()
        bb.limit(n)
        val h = Protocol.readHeader(bb) ?: return
        if (h.sender == myId) return // the relay echoes our own packets
        try {
            listener.onPacket(h, from, bb)
        } catch (e: Exception) {
            Log.w(TAG, "packet handler failed", e)
        }
    }

    /**
     * Privacy mode sender: exactly one same-size encrypted message every
     * [PrivacyFrames.TICK_MS], carrying whatever is queued (or nothing).
     */
    private fun coverLoop(m: MqttConnection, c: PacketCrypto) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var next = System.nanoTime()
        var lastPing = System.currentTimeMillis()
        try {
            while (running && conn === m) {
                val plain = PrivacyFrames.pack(queue)
                val sealed = c.sealBlob(plain, plain.size)
                val msg = ByteArray(1 + sealed.size)
                msg[0] = PrivacyFrames.MAGIC
                System.arraycopy(sealed, 0, msg, 1, sealed.size)
                m.publish(topic, msg)
                val now = System.currentTimeMillis()
                if (now - lastPing >= PING_EVERY_MS) {
                    lastPing = now
                    pingSentAt = now
                    m.ping()
                }
                next += PrivacyFrames.TICK_MS * 1_000_000
                val wait = (next - System.nanoTime()) / 1_000_000
                if (wait > 0) Thread.sleep(wait) else next = System.nanoTime() // fell behind: don't burst
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            m.close()
        }
    }

    @Volatile private var pingSentAt = 0L

    private fun writeLoop(m: MqttConnection) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var lastPing = System.currentTimeMillis()
        try {
            while (running && conn === m) {
                val pkt = queue.pollFirst(500, TimeUnit.MILLISECONDS)
                if (pkt != null) m.publish(topic, pkt)
                val now = System.currentTimeMillis()
                if (now - lastPing >= PING_EVERY_MS) {
                    lastPing = now
                    pingSentAt = now
                    m.ping()
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            m.close() // makes the reader end the session
        }
    }

    private fun setStatus(st: Status, s: RelayServers.Server? = server) {
        status = st
        server = s
        onStatus()
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    private companion object {
        const val TAG = "OnlineTransport"
        const val CONNECT_TIMEOUT_MS = 6000
        const val KEEPALIVE_S = 30
        const val PING_EVERY_MS = 5000L
        /** About 0.3 s of audio; anything older is dropped rather than delivered late. */
        const val MAX_QUEUE = 16
    }
}
