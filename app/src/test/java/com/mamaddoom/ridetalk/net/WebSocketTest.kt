package com.mamaddoom.ridetalk.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WebSocketTest {

    @Test
    fun acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.expectedAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun maskedClientFrameUnmasksToPayload() {
        val sink = ByteArrayOutputStream()
        val payload = ByteArray(300) { it.toByte() }
        WsOutputStream(sink).write(payload, 0, payload.size)
        val f = sink.toByteArray()
        assertEquals(0x82, f[0].toInt() and 0xFF)
        assertEquals(0x80 or 126, f[1].toInt() and 0xFF)
        val len = ((f[2].toInt() and 0xFF) shl 8) or (f[3].toInt() and 0xFF)
        assertEquals(300, len)
        val mask = f.copyOfRange(4, 8)
        val body = ByteArray(len) { (f[8 + it].toInt() xor mask[it and 3].toInt()).toByte() }
        assertArrayEquals(payload, body)
    }

    @Test
    fun serverFramesBecomeOneStreamAndPingsAreAnswered() {
        // ping "hi", then the MQTT bytes split over two binary frames.
        val wire = byteArrayOf(0x89.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte(), 0x82.toByte(), 3, 1, 2, 3, 0x82.toByte(), 2, 4, 5)
        val pongs = ByteArrayOutputStream()
        val input = WsInputStream(ByteArrayInputStream(wire), WsOutputStream(pongs))
        val got = ByteArray(5)
        var n = 0
        while (n < 5) n += input.read(got, n, 5 - n)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), got)
        assertEquals(0x8A, pongs.toByteArray()[0].toInt() and 0xFF) // pong sent back
    }
}
