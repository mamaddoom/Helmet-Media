package com.mamaddoom.ridetalk.net

import java.nio.ByteBuffer

/**
 * Privacy mode (online): instead of one relay message per packet, the phone
 * sends one message every [TICK_MS] whether anyone talks or not, each the
 * same size (a multiple of [BUCKET] bytes) and encrypted as a whole. Inside
 * are zero or more of the ordinary (already sealed) packets plus padding.
 * The relay and the network then see a steady, uniform stream and can't
 * tell speech, silence or music apart.
 *
 * Wire format: MAGIC, then PacketCrypto.sealBlob of:
 *   u8 count, count × (u16 length, packet), zero padding.
 */
object PrivacyFrames {
    const val MAGIC: Byte = 0xFE.toByte()
    const val BUCKET = 256
    const val TICK_MS = 40L
    /** Bytes the wrapper adds around the plain block: magic + nonce + GCM tag. */
    const val OVERHEAD = 1 + 12 + 16
    /** Largest message; packets that don't fit wait for the next tick. */
    const val MAX_WIRE = 4 * BUCKET

    /** Builds the plain block from the front of [queue] (taken packets are removed). */
    fun pack(queue: java.util.Deque<ByteArray>): ByteArray {
        val taken = ArrayList<ByteArray>()
        var used = 1
        while (true) {
            val p = queue.peekFirst() ?: break
            if (used + 2 + p.size + OVERHEAD > MAX_WIRE && taken.isNotEmpty()) break
            queue.pollFirst()
            taken += p
            used += 2 + p.size
            if (taken.size == 255) break
        }
        // Round the whole message up to a multiple of BUCKET (at least one).
        val wire = ((used + OVERHEAD + BUCKET - 1) / BUCKET).coerceAtLeast(1) * BUCKET
        val plain = ByteBuffer.allocate(wire - OVERHEAD)
        plain.put(taken.size.toByte())
        for (p in taken) {
            plain.putShort(p.size.toShort())
            plain.put(p)
        }
        return plain.array()
    }

    /** The packets inside a plain block (padding ignored); empty for a cover message. */
    fun unpack(plain: ByteArray): List<ByteArray> {
        if (plain.isEmpty()) return emptyList()
        val b = ByteBuffer.wrap(plain)
        val n = b.get().toInt() and 0xFF
        val out = ArrayList<ByteArray>(n)
        repeat(n) {
            if (b.remaining() < 2) return out
            val len = b.short.toInt() and 0xFFFF
            if (len > b.remaining()) return out
            out += ByteArray(len).also { b.get(it) }
        }
        return out
    }
}
