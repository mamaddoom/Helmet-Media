package com.mamaddoom.ridetalk.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Just enough WebSocket (RFC 6455) to carry MQTT through an HTTPS gateway,
 * for hosts that only expose a web domain (e.g. Runflare): the MQTT bytes
 * travel inside binary frames, client-to-server frames masked.
 */
object WebSocket {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** Upgrades the connection; throws with the server's answer if it isn't a WebSocket. */
    fun handshake(input: InputStream, output: OutputStream, host: String, path: String) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val req = "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Protocol: mqtt\r\n\r\n"
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()
        val head = readHead(input)
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 101")) throw IOException("not a WebSocket: ${status.take(60)}")
        val accept = head.lineSequence()
            .firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        if (accept != expectedAccept(key)) throw IOException("bad WebSocket accept")
    }

    fun expectedAccept(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed during WebSocket upgrade")
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("WebSocket answer too long")
        }
        return sb.toString()
    }
}

/** Each write becomes one masked binary frame. */
class WsOutputStream(private val raw: OutputStream) : OutputStream() {
    private val rng = SecureRandom()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) = frame(0x2, b, off, len)

    @Synchronized
    fun frame(opcode: Int, b: ByteArray, off: Int, len: Int) {
        val head = java.io.ByteArrayOutputStream(14)
        head.write(0x80 or opcode)
        when {
            len < 126 -> head.write(0x80 or len)
            len < 65536 -> {
                head.write(0x80 or 126)
                head.write(len shr 8)
                head.write(len)
            }
            else -> {
                head.write(0x80 or 127)
                for (i in 7 downTo 0) head.write((len.toLong() shr (8 * i)).toInt())
            }
        }
        val mask = ByteArray(4).also { rng.nextBytes(it) }
        head.write(mask)
        val body = ByteArray(len)
        for (i in 0 until len) body[i] = (b[off + i].toInt() xor mask[i and 3].toInt()).toByte()
        raw.write(head.toByteArray())
        raw.write(body)
        raw.flush()
    }

    override fun flush() = raw.flush()
    override fun close() = raw.close()
}

/** The payload bytes of the server's data frames as one stream; answers pings. */
class WsInputStream(private val raw: InputStream, private val out: WsOutputStream) : InputStream() {
    private var remaining = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (remaining == 0L) if (!nextFrame()) return -1
        val n = raw.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) return -1
        remaining -= n
        return n
    }

    /** Reads frame headers until a data frame starts; false on close. */
    private fun nextFrame(): Boolean {
        while (true) {
            val b0 = raw.read()
            val b1 = raw.read()
            if (b0 < 0 || b1 < 0) return false
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((need() shl 8) or need()).toLong()
            else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or need().toLong() }
            }
            val masked = b1 and 0x80 != 0
            val mask = if (masked) ByteArray(4) { need().toByte() } else null
            when (b0 and 0x0F) {
                0x0, 0x1, 0x2 -> {
                    if (mask != null) throw IOException("masked frame from server")
                    if (len == 0L) continue
                    remaining = len
                    return true
                }
                0x8 -> return false // close
                0x9 -> { // ping: answer with the same payload
                    val p = ByteArray(len.toInt()) { need().toByte() }
                    out.frame(0xA, p, 0, p.size)
                }
                else -> repeat(len.toInt()) { need() } // pong or unknown: skip
            }
        }
    }

    private fun need(): Int {
        val v = raw.read()
        if (v < 0) throw IOException("WebSocket closed")
        return v
    }
}
