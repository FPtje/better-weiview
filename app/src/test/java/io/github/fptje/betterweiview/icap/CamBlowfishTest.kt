package io.github.fptje.betterweiview.icap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CamBlowfishTest {
    private val bf = CamBlowfish(CamBlowfish.DEFAULT_KEY)

    @Test
    fun decryptsRealLogin1Challenge() {
        // LOGIN1_RESP payload captured from the camera (docs/icap-protocol.md test vector).
        val payload = hex("12c2bbdbce9b2664e4ff5bc380b9e4f463b38e1876287b217e")
        val n = payload[0].toInt()
        val plain = bf.decrypt(payload.copyOfRange(1, payload.size))
        assertEquals("26:24:1c:10:0d:ad", String(plain, 0, n - 1))
        assertEquals(0, plain[n - 1].toInt())
    }

    @Test
    fun encryptRoundTrips() {
        val block = stringBlock("admin".toByteArray())
        assertArrayEquals(block, bf.decrypt(bf.encrypt(block)))
    }
}
