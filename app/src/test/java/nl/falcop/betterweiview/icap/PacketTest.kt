package nl.falcop.betterweiview.icap

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketTest {
    @Test
    fun stringBlockPadsIncludingNul() {
        assertEquals(8, stringBlock(ByteArray(0)).size)
        assertEquals(8, stringBlock(ByteArray(7)).size)
        assertEquals(16, stringBlock(ByteArray(8)).size)
        assertArrayEquals(hex("61646d696e000000"), stringBlock("admin".toByteArray()))
    }

    @Test
    fun packetHeaderLayout() {
        assertArrayEquals(hex("05338b110e000400000001000000"), packet(Cmd.PLAY_VIDEO, intLe(1)))
    }

    @Test
    fun readPacketSkipsGarbageBeforeMagic() {
        val bytes = hex("0533ff") + packet(Cmd.VIDEO, byteArrayOf(7, 8)) + packet(Cmd.KEEPALIVE)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val first = input.readPacket()
        assertEquals(Cmd.VIDEO, first.cmd)
        assertArrayEquals(byteArrayOf(7, 8), first.payload)
        assertEquals(Cmd.KEEPALIVE, input.readPacket().cmd)
    }
}
