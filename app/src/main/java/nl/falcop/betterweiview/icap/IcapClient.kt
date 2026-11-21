package nl.falcop.betterweiview.icap

import android.net.Network
import android.os.SystemClock
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class IcapException(message: String) : IOException(message)

/**
 * Clean-room client for the camera's live-video protocol (docs/icap-protocol.md).
 * Sockets are created through [network] so traffic stays on the camera's Wi-Fi
 * even though that network has no internet.
 */
class IcapClient(
    private val network: Network,
    private val host: String = Camera.HOST,
    private val port: Int = Camera.PORT,
    private val user: String = Camera.USER,
    private val password: String = Camera.PASSWORD,
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
            output.send(
                packet(Cmd.P2P_TYPE_NOTIFY, byteArrayOf(0)) +
                    packet(Cmd.PLAY_VIDEO, intLe(stream)) +
                    packet(Cmd.SET_VIDEO_PERFORMANCE, byteArrayOf(3, 1))
            )

            val assembler = FrameAssembler()
            var lastSent = SystemClock.elapsedRealtime()
            while (true) {
                // The camera drops silent sessions. Frames arrive ~19/s, so checking
                // here is frequent enough.
                val now = SystemClock.elapsedRealtime()
                if (now - lastSent >= KEEPALIVE_INTERVAL_MS) {
                    output.send(packet(Cmd.KEEPALIVE))
                    lastSent = now
                }
                val p = input.readPacket()
                assembler.accept(p.cmd, p.payload)?.let { emit(it) }
            }
        } finally {
            socket.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun login(input: DataInputStream, output: OutputStream) {
        output.send(packet(Cmd.LOGIN1_REQ))
        val challenge = input.readPacket()
        if (challenge.cmd != Cmd.LOGIN1_RESP || challenge.payload.isEmpty()) {
            throw IcapException("unexpected login1 reply (cmd ${challenge.cmd})")
        }
        // Payload: u8 n = len + 1, then Blowfish(camera id block). It only has to be well formed.
        val idLength = (challenge.payload[0].toInt() and 0xFF) - 1
        if (challenge.payload.size != 1 + stringBlockSize(idLength)) {
            throw IcapException("malformed login1 reply")
        }

        output.send(packet(Cmd.LOGIN2_REQ, encryptedString(user) + encryptedString(password)))
        val reply = input.readPacket()
        if (reply.cmd != Cmd.LOGIN2_RESP || reply.payload.size != 5) {
            throw IcapException("unexpected login2 reply (cmd ${reply.cmd})")
        }
        when (val code = reply.payload[0].toInt()) {
            0 -> Unit
            1 -> throw IcapException("camera rejected the login")
            2 -> throw IcapException("camera has too many sessions")
            else -> throw IcapException("login failed (code $code)")
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 5_000
        const val KEEPALIVE_INTERVAL_MS = 10_000L

        val cipher by lazy { CamBlowfish(CamBlowfish.DEFAULT_KEY) }

        /** u8 len + 1, then the encrypted string block. */
        fun encryptedString(s: String): ByteArray {
            val bytes = s.toByteArray()
            return byteArrayOf((bytes.size + 1).toByte()) + cipher.encrypt(stringBlock(bytes))
        }

        fun OutputStream.send(bytes: ByteArray) {
            write(bytes)
            flush()
        }
    }
}
