package app.phoneproof.core

import java.math.BigInteger

/**
 * Minimal, strict DER reader — just enough for Android key attestation records and
 * ECDSA signatures. No external dependency (BouncyCastle is stripped/old on Android).
 *
 * Supports multi-byte ("high tag number") tags, which the attestation AuthorizationList
 * uses heavily, e.g. [704] rootOfTrust = 0xBF 0x85 0x40.
 */
class Der private constructor(
    /** Tag class bits (0x00 universal, 0x40 application, 0x80 context, 0xC0 private). */
    val tagClass: Int,
    val constructed: Boolean,
    /** Tag number (for universal: 2 INTEGER, 4 OCTET STRING, 16 SEQUENCE, ...). */
    val tagNumber: Int,
    private val buf: ByteArray,
    private val valueStart: Int,
    val length: Int,
    /** Offset just past this element in [buf]. */
    val end: Int,
) {
    /** Convenience id comparable with the constants below (universal tags only). */
    val tag: Int get() = if (tagClass == 0) tagNumber else -1

    val value: ByteArray get() = buf.copyOfRange(valueStart, valueStart + length)

    val isContext: Boolean get() = tagClass == CLASS_CONTEXT

    fun children(): List<Der> {
        require(constructed) { "Element is not constructed (tag $tagNumber)" }
        val out = ArrayList<Der>()
        var pos = valueStart
        val stop = valueStart + length
        while (pos < stop) {
            val child = read(buf, pos, stop)
            out += child
            pos = child.end
        }
        require(pos == stop) { "Trailing bytes inside constructed element" }
        return out
    }

    /** For [n] EXPLICIT wrappers: the single inner element. */
    fun explicitInner(): Der {
        val c = children()
        require(c.size == 1) { "EXPLICIT tag [$tagNumber] must wrap exactly one element" }
        return c[0]
    }

    fun asBigInteger(): BigInteger {
        require(tag == INTEGER || tag == ENUMERATED) { "Expected INTEGER/ENUMERATED, got tag $tagNumber" }
        require(length > 0) { "Empty INTEGER" }
        return BigInteger(value)
    }

    fun asLong(): Long = asBigInteger().longValueExact()
    fun asInt(): Int = asBigInteger().intValueExact()

    fun asOctets(): ByteArray {
        require(tag == OCTET_STRING) { "Expected OCTET STRING, got tag $tagNumber" }
        return value
    }

    fun asBoolean(): Boolean {
        require(tag == BOOLEAN && length == 1) { "Expected BOOLEAN" }
        return when (buf[valueStart].toInt() and 0xFF) {
            0x00 -> false
            0xFF -> true
            else -> throw IllegalArgumentException("Non-DER BOOLEAN value")
        }
    }

    companion object {
        const val BOOLEAN = 1
        const val INTEGER = 2
        const val BIT_STRING = 3
        const val OCTET_STRING = 4
        const val NULL = 5
        const val OID = 6
        const val ENUMERATED = 10
        const val SEQUENCE = 16
        const val SET = 17

        const val CLASS_CONTEXT = 0x80

        /** Parse exactly one element spanning the whole input. */
        fun parse(bytes: ByteArray): Der {
            val d = read(bytes, 0, bytes.size)
            require(d.end == bytes.size) { "Trailing bytes after DER element" }
            return d
        }

        private fun read(buf: ByteArray, start: Int, limit: Int): Der {
            var p = start
            fun next(): Int {
                require(p < limit) { "Truncated DER" }
                return buf[p++].toInt() and 0xFF
            }
            val first = next()
            val cls = first and 0xC0
            val cons = (first and 0x20) != 0
            var num = first and 0x1F
            if (num == 0x1F) {
                num = 0
                var b: Int
                var count = 0
                do {
                    b = next()
                    require(!(count == 0 && b == 0x80)) { "Non-minimal tag encoding" }
                    num = (num shl 7) or (b and 0x7F)
                    require(++count <= 4) { "Tag number too large" }
                } while (b and 0x80 != 0)
            }
            val lenByte = next()
            val len: Int = if (lenByte < 0x80) {
                lenByte
            } else {
                val n = lenByte and 0x7F
                require(n in 1..4) { "Unsupported DER length form" }
                var l = 0
                repeat(n) { l = (l shl 8) or next() }
                require(l >= 0x80) { "Non-minimal DER length" }
                l
            }
            val valueStart = p
            require(len >= 0 && valueStart + len <= limit) { "DER length exceeds input" }
            return Der(cls, cons, num, buf, valueStart, len, valueStart + len)
        }
    }
}
