package io.github.fptje.betterweiview.icap

// Little-endian integer helpers. All multi-byte fields in the protocol are little-endian.

internal fun readU16Le(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

internal fun readS16Le(b: ByteArray, off: Int): Int = readU16Le(b, off).toShort().toInt()

internal fun readIntLe(b: ByteArray, off: Int): Int =
    readU16Le(b, off) or (readU16Le(b, off + 2) shl 16)

internal fun writeIntLe(b: ByteArray, off: Int, v: Int) {
    b[off] = v.toByte()
    b[off + 1] = (v ushr 8).toByte()
    b[off + 2] = (v ushr 16).toByte()
    b[off + 3] = (v ushr 24).toByte()
}

internal fun intLe(v: Int): ByteArray = ByteArray(4).also { writeIntLe(it, 0, v) }
