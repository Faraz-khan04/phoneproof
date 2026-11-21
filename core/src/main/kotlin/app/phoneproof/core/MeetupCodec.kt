package app.phoneproof.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Meet-up mode wire format (offline, phone to phone, QR only). See "Meet-up protocol" in the spec.
 *
 * Challenge (buyer → seller), one QR:   pp1:c:<nonce b64url, 22 chars>:<pinned keyId or ->
 * Answer (seller → buyer), animated QR:  pp1:f:<i>/<n>:<sha256(payload) first 8 hex>:<b64url chunk>
 *   payload = raw DEFLATE of the envelope JSON; frames may be scanned in any order.
 */
object MeetupCodec {

    data class Challenge(val nonce: String, val pinnedKeyId: String?)

    fun newChallenge(pinnedKeyId: String?): Challenge {
        val n = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Challenge(Encoding.b64url(n), pinnedKeyId)
    }

    fun encodeChallenge(c: Challenge) = "pp1:c:${c.nonce}:${c.pinnedKeyId ?: "-"}"

    fun parseChallenge(text: String): Challenge? {
        val p = text.trim().split(':')
        if (p.size != 4 || p[0] != "pp1" || p[1] != "c") return null
        if (!Regex("[A-Za-z0-9_-]{22}").matches(p[2])) return null
        val pin = p[3].takeIf { it != "-" }
        if (pin != null && !Regex("[0-9a-f]{16}").matches(pin)) return null
        return Challenge(p[2], pin)
    }

    const val CHUNK = 700

    fun frames(envelopeJson: String): List<String> {
        val raw = deflate(envelopeJson.toByteArray(Charsets.UTF_8))
        val tag = Encoding.hex(Encoding.sha256(raw)).take(8)
        val b64 = Encoding.b64url(raw)
        val parts = b64.chunked(CHUNK)
        return parts.mapIndexed { i, s -> "pp1:f:${i + 1}/${parts.size}:$tag:$s" }
    }

    /** Collects frames in any order; [result] becomes non-null when all frames for one tag arrived. */
    class Reassembler {
        private var tag: String? = null
        private var total = 0
        private val parts = HashMap<Int, String>()
        var result: String? = null; private set
        val received get() = parts.size
        val expected get() = total

        /** Returns true if the text was a valid frame. */
        fun add(text: String): Boolean {
            val p = text.trim().split(':', limit = 5)
            if (p.size != 5 || p[0] != "pp1" || p[1] != "f") return false
            val (iStr, nStr) = p[2].split('/').takeIf { it.size == 2 } ?: return false
            val i = iStr.toIntOrNull() ?: return false
            val n = nStr.toIntOrNull() ?: return false
            if (n !in 1..64 || i !in 1..n) return false
            if (tag != null && tag != p[3]) { tag = null; parts.clear() } // a new answer started
            tag = p[3]; total = n
            parts[i] = p[4]
            if (parts.size == total) {
                val raw = Encoding.fromB64url((1..total).joinToString("") { parts.getValue(it) })
                if (Encoding.hex(Encoding.sha256(raw)).take(8) != tag) { parts.clear(); return true } // corrupted, rescan
                result = String(inflate(raw), Charsets.UTF_8)
            }
            return true
        }
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setInput(b)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() <= ReportEnvelope.MAX_BYTES) { "Answer too large" }
        }
        inf.end(); return out.toByteArray()
    }
}
