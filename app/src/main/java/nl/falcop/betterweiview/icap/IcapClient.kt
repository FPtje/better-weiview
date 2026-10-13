package nl.falcop.betterweiview.icap

import android.net.Network
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** One decoded video frame as sent by the camera. */
class VideoFrame(
    val codec: Int,
    val resolution: Int,
    val isKey: Boolean,
    /** Camera clock in 10 ms units. */
    val tick: Int,
    val data: ByteArray,
) {
    companion object {
        const val CODEC_MJPEG = 0
        const val CODEC_H264 = 2
        const val CODEC_H265 = 3
    }
}

class IcapException(message: String) : IOException(message)

/**
 * Clean-room client for the camera's live-video protocol (docs/icap-protocol.md).
 * Sockets are created through [network] so traffic stays on the camera's Wi-Fi
 * even though that network has no internet.
 */
class IcapClient(
    private val network: Network,
    private val host: String = DEFAULT_HOST,
    private val port: Int = 80,
    private val user: String = "admin",
    private val password: String = "",
    private val stream: Int = 0,
) {
    /** Connects, logs in and emits frames until cancelled or the connection drops. */
    fun frames(): Flow<VideoFrame> = flow {
        // Cancellation is observed at each emit (the camera sends ~19 frames/s); if the
        // stream stalls, the read timeout ends the flow instead.
        val socket = network.socketFactory.createSocket() as Socket
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
            val output = socket.getOutputStream()
            login(input, output)
            output.write(
                packet(CMD_P2P_TYPE_NOTIFY, byteArrayOf(0)) +
                    packet(CMD_PLAY_VIDEO, intLe(stream)) +
                    packet(CMD_SET_VIDEO_PERFORMANCE, byteArrayOf(3, 1))
            )
            output.flush()

            val assembler = FragmentAssembler()
            while (true) {
                val (cmd, payload) = readPacket(input)
                val frame = when (cmd) {
                    CMD_VIDEO -> wholeFrame(payload, extended = false)
                    CMD_VIDEO3 -> wholeFrame(payload, extended = true)
                    CMD_VIDEO2_FRAGMENT -> assembler.add(payload, extended = false)
                    CMD_VIDEO4_FRAGMENT -> assembler.add(payload, extended = true)
                    else -> null
                }
                if (frame != null) emit(frame)
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun login(input: DataInputStream, output: OutputStream) {
        val bf = cipher
        output.write(packet(CMD_LOGIN1_REQ))
        output.flush()
        val (cmd1, challenge) = readPacket(input)
        if (cmd1 != CMD_LOGIN1_RESP || challenge.isEmpty()) {
            throw IcapException("unexpected login1 reply (cmd $cmd1)")
        }
        // Payload: u8 n, then Blowfish(camera id block). We only need it to be well formed.
        val blockLen = ((challenge[0].toInt() and 0xFF) + 7) and 0x1F8
        if (challenge.size != blockLen + 1) throw IcapException("malformed login1 reply")

        val user = user.toByteArray()
        val pwd = password.toByteArray()
        val body = byteArrayOf((user.size + 1).toByte()) + bf.encrypt(stringBlock(user)) +
            byteArrayOf((pwd.size + 1).toByte()) + bf.encrypt(stringBlock(pwd))
        output.write(packet(CMD_LOGIN2_REQ, body))
        output.flush()
        val (cmd2, result) = readPacket(input)
        if (cmd2 != CMD_LOGIN2_RESP || result.size != 5) {
            throw IcapException("unexpected login2 reply (cmd $cmd2)")
        }
        when (val code = result[0].toInt()) {
            0 -> Unit
            1 -> throw IcapException("camera rejected the login")
            2 -> throw IcapException("camera has too many sessions")
            else -> throw IcapException("login failed (code $code)")
        }
    }

    private fun readPacket(input: DataInputStream): Pair<Int, ByteArray> {
        // Resynchronise on the magic if the stream ever gets misaligned.
        var window = 0
        var seen = 0
        while (seen < 4 || window != MAGIC_INT) {
            val b = input.read()
            if (b < 0) throw EOFException("camera closed the connection")
            window = (window ushr 8) or (b shl 24)
            seen++
        }
        val header = ByteArray(6)
        input.readFully(header)
        val cmd = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
        val len = readIntLe(header, 2)
        if (len < 0 || len > MAX_PAYLOAD) throw IcapException("bad packet length $len")
        val payload = ByteArray(len)
        input.readFully(payload)
        return cmd to payload
    }

    private class Header(val codec: Int, val resolution: Int, val isKey: Boolean, val tick: Int, val length: Int)

    private class FragmentAssembler {
        private var header: Header? = null
        private var buffer = ByteArray(0)
        private var filled = 0

        fun add(payload: ByteArray, extended: Boolean): VideoFrame? {
            if (payload.isEmpty()) return null
            val flags = payload[0].toInt()
            var offset = 1
            if (flags and 1 != 0) {
                val h = parseHeader(payload, 1, extended) ?: return reset()
                header = h
                if (buffer.size < h.length) buffer = ByteArray(h.length)
                filled = 0
                offset += headerSize(extended)
            }
            val h = header ?: return null
            if (flags and 2 != 0) {
                val n = payload.size - offset
                if (filled + n > h.length) return reset()
                System.arraycopy(payload, offset, buffer, filled, n)
                filled += n
            }
            if (filled < h.length) return null
            header = null
            return VideoFrame(h.codec, h.resolution, h.isKey, h.tick, buffer.copyOf(h.length))
        }

        private fun reset(): VideoFrame? {
            header = null
            filled = 0
            return null
        }
    }

    companion object {
        const val DEFAULT_HOST = "192.168.1.1"

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_PAYLOAD = 8 * 1024 * 1024

        // Magic 05 33 8B 11 read as a little-endian int.
        private const val MAGIC_INT = 0x118B3305
        private val MAGIC = byteArrayOf(0x05, 0x33, 0x8B.toByte(), 0x11)

        const val CMD_LOGIN1_REQ = 0x00
        const val CMD_LOGIN1_RESP = 0x01
        const val CMD_LOGIN2_REQ = 0x02
        const val CMD_LOGIN2_RESP = 0x03
        const val CMD_PLAY_VIDEO = 0x0E
        const val CMD_VIDEO = 0x11
        const val CMD_SET_VIDEO_PERFORMANCE = 0x1C
        const val CMD_VIDEO2_FRAGMENT = 0x25
        const val CMD_P2P_TYPE_NOTIFY = 0x2F
        const val CMD_VIDEO3 = 0x34
        const val CMD_VIDEO4_FRAGMENT = 0x35

        private val cipher by lazy { CamBlowfish(CamBlowfish.DEFAULT_KEY) }

        fun packet(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray =
            MAGIC + byteArrayOf(cmd.toByte(), (cmd ushr 8).toByte()) + intLe(payload.size) + payload

        /** The string, a NUL, then zero filler up to (len + 8) & ~7 bytes. */
        fun stringBlock(s: ByteArray): ByteArray = s.copyOf((s.size + 8) and 7.inv())

        private fun intLe(v: Int) = ByteArray(4).also { writeIntLe(it, 0, v) }

        private fun headerSize(extended: Boolean) = if (extended) 23 else 17

        private fun parseHeader(b: ByteArray, off: Int, extended: Boolean): Header? {
            if (b.size < off + headerSize(extended)) return null
            val lenOff = off + if (extended) 19 else 13
            return Header(
                codec = b[off].toInt() and 0xFF,
                resolution = b[off + 1].toInt() and 0xFF,
                isKey = b[off + 4].toInt() != 0,
                tick = readIntLe(b, off + 5),
                length = readIntLe(b, lenOff).takeIf { it in 0..MAX_PAYLOAD } ?: return null,
            )
        }

        private fun wholeFrame(payload: ByteArray, extended: Boolean): VideoFrame? {
            val h = parseHeader(payload, 0, extended) ?: return null
            val start = headerSize(extended)
            if (payload.size < start + h.length) return null
            return VideoFrame(h.codec, h.resolution, h.isKey, h.tick, payload.copyOfRange(start, start + h.length))
        }
    }
}
