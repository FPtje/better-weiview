package io.github.fptje.betterweiview.icap

/** One complete video frame from the camera (docs/icap-protocol.md, "Video frames"). */
class VideoFrame(
    val codec: Int,
    /** Accelerometer reading; only present in extended (0x34/0x35) frames. */
    val accel: Accel?,
    val data: ByteArray,
) {
    class Accel(val x: Int, val y: Int, val z: Int)

    companion object {
        const val CODEC_MJPEG = 0
    }
}

/**
 * Turns video packets (whole or fragmented, plain or extended header) into frames.
 * Malformed input drops the frame in progress instead of failing the stream.
 */
internal class FrameAssembler {
    private var header: Header? = null
    private var buffer = ByteArray(0)
    private var filled = 0

    /** Returns the frame that [payload] completes, or null. Ignores non-video commands. */
    fun accept(cmd: Int, payload: ByteArray): VideoFrame? = when (cmd) {
        Cmd.VIDEO -> whole(payload, extended = false)
        Cmd.VIDEO3 -> whole(payload, extended = true)
        Cmd.VIDEO2_FRAGMENT -> fragment(payload, extended = false)
        Cmd.VIDEO4_FRAGMENT -> fragment(payload, extended = true)
        else -> null
    }

    private fun whole(payload: ByteArray, extended: Boolean): VideoFrame? {
        val h = Header.parse(payload, 0, extended) ?: return null
        val start = Header.size(extended)
        if (payload.size < start + h.length) return null
        return h.frame(payload.copyOfRange(start, start + h.length))
    }

    private fun fragment(payload: ByteArray, extended: Boolean): VideoFrame? {
        if (payload.isEmpty()) return null
        val flags = payload[0].toInt()
        var offset = 1
        if (flags and FLAG_START != 0) {
            val h = Header.parse(payload, offset, extended) ?: return reset()
            header = h
            if (buffer.size < h.length) buffer = ByteArray(h.length)
            filled = 0
            offset += Header.size(extended)
        }
        val h = header ?: return null
        if (flags and FLAG_DATA != 0) {
            val n = payload.size - offset
            if (filled + n > h.length) return reset()
            System.arraycopy(payload, offset, buffer, filled, n)
            filled += n
        }
        if (filled < h.length) return null
        header = null
        return h.frame(buffer.copyOf(h.length))
    }

    private fun reset(): VideoFrame? {
        header = null
        filled = 0
        return null
    }

    private class Header(val codec: Int, val accel: VideoFrame.Accel?, val length: Int) {
        fun frame(data: ByteArray) = VideoFrame(codec, accel, data)

        companion object {
            fun size(extended: Boolean) = if (extended) 23 else 17

            fun parse(b: ByteArray, off: Int, extended: Boolean): Header? {
                if (b.size < off + size(extended)) return null
                val length = readIntLe(b, off + size(extended) - 4)
                if (length !in 0..MAX_PAYLOAD) return null
                val accel = if (extended) {
                    VideoFrame.Accel(readS16Le(b, off + 13), readS16Le(b, off + 15), readS16Le(b, off + 17))
                } else {
                    null
                }
                return Header(codec = b[off].toInt() and 0xFF, accel = accel, length = length)
            }
        }
    }

    private companion object {
        const val FLAG_START = 1
        const val FLAG_DATA = 2
    }
}
