package nl.falcop.betterweiview.icap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CamBlowfishTest {
    private val bf = CamBlowfish(CamBlowfish.DEFAULT_KEY)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

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
        val block = IcapClient.stringBlock("admin".toByteArray())
        assertEquals(8, block.size)
        assertArrayEquals(block, bf.decrypt(bf.encrypt(block)))
    }

    @Test
    fun stringBlockPadsIncludingNul() {
        assertEquals(8, IcapClient.stringBlock(ByteArray(0)).size)
        assertEquals(8, IcapClient.stringBlock(ByteArray(7)).size)
        assertEquals(16, IcapClient.stringBlock(ByteArray(8)).size)
    }

    @Test
    fun packetHeaderLayout() {
        val p = IcapClient.packet(0x0E, byteArrayOf(1, 0, 0, 0))
        assertArrayEquals(hex("05338b110e000400000001000000"), p)
    }
}
