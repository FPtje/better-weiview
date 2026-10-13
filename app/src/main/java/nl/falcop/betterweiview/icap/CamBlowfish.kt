package nl.falcop.betterweiview.icap

import java.math.BigInteger

/**
 * The camera's Blowfish variant (see docs/icap-protocol.md, "Cipher"):
 * zero-initialised P-array, pi S-boxes with five patched entries, and
 * blocks made of two little-endian 32-bit words.
 */
class CamBlowfish(key: ByteArray) {
    private val p = IntArray(18)
    private val s = Array(4) { SBOX_INIT[it].copyOf() }

    init {
        var j = 0
        for (i in 0 until 18) {
            var w = 0
            repeat(4) {
                w = (w shl 8) or (key[j].toInt() and 0xFF)
                j = (j + 1) % key.size
            }
            p[i] = p[i] xor w
        }
        val block = IntArray(2)
        for (i in 0 until 18 step 2) {
            encipher(block)
            p[i] = block[0]; p[i + 1] = block[1]
        }
        for (box in s) {
            for (i in 0 until 256 step 2) {
                encipher(block)
                box[i] = block[0]; box[i + 1] = block[1]
            }
        }
    }

    private fun f(x: Int): Int =
        ((s[0][x ushr 24] + s[1][(x ushr 16) and 0xFF]) xor s[2][(x ushr 8) and 0xFF]) +
            s[3][x and 0xFF]

    private fun encipher(lr: IntArray) {
        var l = lr[0]
        var r = lr[1]
        for (i in 0 until 16) {
            l = l xor p[i]
            r = r xor f(l)
            val t = l; l = r; r = t
        }
        lr[0] = r xor p[17]
        lr[1] = l xor p[16]
    }

    private fun decipher(lr: IntArray) {
        var l = lr[0]
        var r = lr[1]
        for (i in 17 downTo 2) {
            l = l xor p[i]
            r = r xor f(l)
            val t = l; l = r; r = t
        }
        lr[0] = r xor p[0]
        lr[1] = l xor p[1]
    }

    fun encrypt(data: ByteArray): ByteArray = apply(data, ::encipher)

    fun decrypt(data: ByteArray): ByteArray = apply(data, ::decipher)

    private inline fun apply(data: ByteArray, op: (IntArray) -> Unit): ByteArray {
        require(data.size % 8 == 0) { "Blowfish data must be a multiple of 8 bytes" }
        val out = data.copyOf()
        val lr = IntArray(2)
        for (off in out.indices step 8) {
            lr[0] = readIntLe(out, off)
            lr[1] = readIntLe(out, off + 4)
            op(lr)
            writeIntLe(out, off, lr[0])
            writeIntLe(out, off + 4, lr[1])
        }
        return out
    }

    companion object {
        val DEFAULT_KEY = "hello kitty and kgb/cia 2011 COPYRIGHT@REECAM 5460".toByteArray()

        /** S-boxes: the 1024 pi words after the first 18, with the camera's typos applied. */
        private val SBOX_INIT: Array<IntArray> by lazy {
            val words = piHexWords(18 + 1024)
            val boxes = Array(4) { b -> IntArray(256) { words[18 + 256 * b + it] } }
            boxes[0][227] = 0x56DFA6AA
            boxes[0][250] = 0xFF340528.toInt()
            boxes[2][25] = 0x55FA3941
            boxes[2][187] = 0x915F958E.toInt()
            boxes[3][23] = 0x42D04DC6
            boxes
        }

        /** First [count] 32-bit words of pi's fractional hex expansion (Machin's formula). */
        private fun piHexWords(count: Int): IntArray {
            val guardBits = 64
            val prec = count * 32 + guardBits
            fun arctanInv(x: Int): BigInteger {
                val bx = BigInteger.valueOf(x.toLong())
                val x2 = bx * bx
                var term = BigInteger.ONE.shiftLeft(prec) / bx
                var sum = term
                var n = 1L
                var add = false
                while (term.signum() != 0) {
                    term /= x2
                    n += 2
                    val t = term / BigInteger.valueOf(n)
                    sum = if (add) sum + t else sum - t
                    add = !add
                }
                return sum
            }
            val pi = arctanInv(5).shiftLeft(4) - arctanInv(239).shiftLeft(2)
            val frac = (pi - BigInteger.valueOf(3).shiftLeft(prec)).shiftRight(guardBits)
            val mask = BigInteger.valueOf(0xFFFFFFFFL)
            return IntArray(count) { i ->
                frac.shiftRight(32 * (count - 1 - i)).and(mask).toLong().toInt()
            }
        }
    }
}

internal fun readIntLe(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)

internal fun writeIntLe(b: ByteArray, off: Int, v: Int) {
    b[off] = v.toByte()
    b[off + 1] = (v ushr 8).toByte()
    b[off + 2] = (v ushr 16).toByte()
    b[off + 3] = (v ushr 24).toByte()
}
