package app.phoneproof.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic JSON used for everything we sign.
 *
 * It is the integer-only subset of RFC 8785 (JCS):
 *  - object keys sorted by UTF-16 code units (Kotlin's String.compareTo does exactly this,
 *    and so does JavaScript's default Array.prototype.sort on strings),
 *  - no insignificant whitespace,
 *  - strings escaped minimally (\" \\ \b \f \n \r \t, other control chars as \u00xx),
 *  - numbers must be integers within +/- 2^53-1 so JavaScript reads them exactly.
 *    Floats are rejected on purpose: store deci-degrees, percentages, etc. as ints.
 *
 * The browser verifier re-implements this in ~30 lines; both must produce identical bytes.
 */
object CanonicalJson {

    private const val MAX_SAFE = 9_007_199_254_740_991L // 2^53 - 1
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    fun encode(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    fun encodeToBytes(element: JsonElement): ByteArray = encode(element).toByteArray(Charsets.UTF_8)

    private fun write(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> writePrimitive(e, out)
            is JsonArray -> {
                out.append('[')
                e.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                e.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(e.getValue(key), out)
                }
                out.append('}')
            }
        }
    }

    private fun writePrimitive(p: JsonPrimitive, out: StringBuilder) {
        if (p.isString) {
            writeString(p.content, out)
            return
        }
        val c = p.content
        when {
            c == "true" || c == "false" -> out.append(c)
            INTEGER.matches(c) -> {
                val v = c.toLongOrNull()
                    ?: throw IllegalArgumentException("Integer out of range: $c")
                require(v in -MAX_SAFE..MAX_SAFE) { "Integer outside JS safe range: $c" }
                out.append(v.toString())
            }
            else -> throw IllegalArgumentException("Only integers are allowed in signed JSON, got: $c")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append("\\u00")
                    out.append(HEX[ch.code shr 4])
                    out.append(HEX[ch.code and 0xF])
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
