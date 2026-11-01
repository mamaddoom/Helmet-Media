package com.mamaddoom.ridetalk.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class PrivacyFramesTest {
    private val crypto = PacketCrypto("sunset-ride-2026", 1000, PacketCrypto.ONLINE_SALT)

    private fun wire(queue: ArrayDeque<ByteArray>): ByteArray {
        val plain = PrivacyFrames.pack(queue)
        return byteArrayOf(PrivacyFrames.MAGIC) + crypto.sealBlob(plain, plain.size)
    }

    @Test
    fun silenceVoiceAndMusicLookTheSameSize() {
        val silence = wire(ArrayDeque())
        val voice = wire(ArrayDeque(listOf(ByteArray(140) { 1 })))
        val beaconAndVoice = wire(ArrayDeque(listOf(ByteArray(140), ByteArray(70))))
        assertEquals(PrivacyFrames.BUCKET, silence.size)
        assertEquals(silence.size, voice.size)
        assertEquals(silence.size, beaconAndVoice.size)
        // Music packets fill a bigger (but still bucketed) message.
        assertEquals(0, wire(ArrayDeque(listOf(ByteArray(380)))).size % PrivacyFrames.BUCKET)
    }

    @Test
    fun packetsRoundTripAndCoverIsEmpty() {
        val a = ByteArray(140) { it.toByte() }
        val b = ByteArray(60) { (it * 3).toByte() }
        val w = wire(ArrayDeque(listOf(a, b)))
        val got = PrivacyFrames.unpack(crypto.openBlob(w, 1, w.size - 1)!!)
        assertEquals(2, got.size)
        assertArrayEquals(a, got[0])
        assertArrayEquals(b, got[1])
        val cover = wire(ArrayDeque())
        assertTrue(PrivacyFrames.unpack(crypto.openBlob(cover, 1, cover.size - 1)!!).isEmpty())
    }

    @Test
    fun otherGroupsCannotOpenIt() {
        val w = wire(ArrayDeque(listOf(ByteArray(50))))
        val other = PacketCrypto("another-group-code", 1000, PacketCrypto.ONLINE_SALT)
        assertNull(other.openBlob(w, 1, w.size - 1))
    }

    @Test
    fun tooMuchWaitsForTheNextTick() {
        val q = ArrayDeque((1..10).map { ByteArray(400) })
        val plain = PrivacyFrames.pack(q)
        assertTrue(plain.size + PrivacyFrames.OVERHEAD <= PrivacyFrames.MAX_WIRE)
        assertTrue(q.isNotEmpty())
    }
}
