package nl.falcop.betterweiview.icap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameAssemblerTest {
    /** Extended (23-byte) header: MJPEG, 480×480, accelerometer (-144, 1888, 17568). */
    private fun extendedHeader(length: Int): ByteArray {
        val h = ByteArray(23)
        h[1] = 9
        h[4] = 1
        h[13] = 0x70; h[14] = 0xFF.toByte() // -144
        h[15] = 0x60; h[16] = 0x07 // 1888
        h[17] = 0xA0.toByte(); h[18] = 0x44 // 17568
        writeIntLe(h, 19, length)
        return h
    }

    @Test
    fun wholeExtendedFrame() {
        val frame = FrameAssembler().accept(Cmd.VIDEO3, extendedHeader(3) + byteArrayOf(1, 2, 3))!!
        assertEquals(VideoFrame.CODEC_MJPEG, frame.codec)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.data)
        val accel = frame.accel!!
        assertEquals(listOf(-144, 1888, 17568), listOf(accel.x, accel.y, accel.z))
    }

    @Test
    fun plainFrameHasNoAccelerometer() {
        val header = ByteArray(17).also { writeIntLe(it, 13, 1) }
        val frame = FrameAssembler().accept(Cmd.VIDEO, header + byteArrayOf(9))!!
        assertNull(frame.accel)
        assertArrayEquals(byteArrayOf(9), frame.data)
    }

    @Test
    fun fragmentsAreJoined() {
        val a = FrameAssembler()
        assertNull(a.accept(Cmd.VIDEO4_FRAGMENT, byteArrayOf(3) + extendedHeader(4) + byteArrayOf(1, 2)))
        val frame = a.accept(Cmd.VIDEO4_FRAGMENT, byteArrayOf(2, 3, 4))!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.data)
    }

    @Test
    fun overflowingFragmentDropsTheFrame() {
        val a = FrameAssembler()
        a.accept(Cmd.VIDEO4_FRAGMENT, byteArrayOf(1) + extendedHeader(2))
        assertNull(a.accept(Cmd.VIDEO4_FRAGMENT, byteArrayOf(2, 1, 2, 3)))
        assertNull(a.accept(Cmd.VIDEO4_FRAGMENT, byteArrayOf(2, 1, 2)))
    }

    @Test
    fun otherCommandsAreIgnored() {
        assertNull(FrameAssembler().accept(Cmd.KEEPALIVE, ByteArray(0)))
    }
}
