package nl.falcop.betterweiview.icap

import java.io.DataInputStream
import java.io.EOFException

/** Command codes (docs/icap-protocol.md, "Session"). */
internal object Cmd {
    const val LOGIN1_REQ = 0x00
    const val LOGIN1_RESP = 0x01
    const val LOGIN2_REQ = 0x02
    const val LOGIN2_RESP = 0x03
    const val KEEPALIVE = 0x04
    const val PLAY_VIDEO = 0x0E
    const val VIDEO = 0x11
    const val SET_VIDEO_PERFORMANCE = 0x1C
    const val VIDEO2_FRAGMENT = 0x25
    const val P2P_TYPE_NOTIFY = 0x2F
    const val VIDEO3 = 0x34
    const val VIDEO4_FRAGMENT = 0x35
}

internal class Packet(val cmd: Int, val payload: ByteArray)

internal const val MAX_PAYLOAD = 8 * 1024 * 1024

private val MAGIC = byteArrayOf(0x05, 0x33, 0x8B.toByte(), 0x11)
private val MAGIC_INT = readIntLe(MAGIC, 0)

/** Frames [payload] as magic, u16 command, u32 length, payload. */
internal fun packet(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray =
    MAGIC + byteArrayOf(cmd.toByte(), (cmd ushr 8).toByte()) + intLe(payload.size) + payload

/** Size of the encrypted block for a string of [length] bytes: string + NUL, rounded up to 8. */
internal fun stringBlockSize(length: Int): Int = (length + 8) and 7.inv()

/** The string, a NUL, then zero filler up to [stringBlockSize]. */
internal fun stringBlock(s: ByteArray): ByteArray = s.copyOf(stringBlockSize(s.size))

/** Reads the next packet, skipping any bytes before the magic. */
internal fun DataInputStream.readPacket(): Packet {
    var window = 0
    var seen = 0
    while (seen < 4 || window != MAGIC_INT) {
        val b = read()
        if (b < 0) throw EOFException("camera closed the connection")
        window = (window ushr 8) or (b shl 24)
        seen++
    }
    val header = ByteArray(6)
    readFully(header)
    val len = readIntLe(header, 2)
    if (len < 0 || len > MAX_PAYLOAD) throw IcapException("bad packet length $len")
    val payload = ByteArray(len)
    readFully(payload)
    return Packet(readU16Le(header, 0), payload)
}
