package app.phoneproof.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/*
 * Report data model, format version 1. See "Report format" in the MVP spec.
 * Built by hand on JsonElement (no serialization compiler plugin) so the exact
 * signed bytes are fully under our control.
 */

enum class TestOutcome { PASS, FAIL, WARN, SKIPPED, UNSUPPORTED }

enum class DecidedBy(val wire: String) { AUTO("auto"), SELLER("seller");
    companion object { fun of(s: String) = entries.first { it.wire == s } }
}

enum class ReportKind(val wire: String) { LISTING("listing"), MEETUP("meetup");
    companion object { fun of(s: String) = entries.first { it.wire == s } }
}

data class TestResult(
    /** Stable code, e.g. "battery.health". Never rename once shipped. */
    val id: String,
    val outcome: TestOutcome,
    val by: DecidedBy,
    /** Optional integer measurements, max 8 keys. */
    val measurements: Map<String, Long> = emptyMap(),
) {
    init {
        require(ID_RE.matches(id)) { "Bad test id: $id" }
        require(measurements.size <= 8) { "Too many measurements for $id" }
        measurements.keys.forEach { require(KEY_RE.matches(it)) { "Bad measurement key: $it" } }
    }
    companion object {
        private val ID_RE = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*")
        private val KEY_RE = Regex("[a-z][a-z0-9_]{0,15}")
    }
}

data class DeviceClaims(
    val brand: String,
    val model: String,
    val manufacturer: String,
    val sdk: Int,
    /** Build.VERSION.SECURITY_PATCH, "YYYY-MM-DD". */
    val patch: String,
)

data class PhotoRef(val kind: String, val sha256Hex: String, val takenAtMs: Long)

data class ImeiClaim(val value: String, val source: String /* "ocr" | "typed" */) {
    init { require(Regex("[0-9]{15}").matches(value) && luhnOk(value)) { "IMEI must be 15 digits with valid Luhn check" } }
    companion object {
        fun luhnOk(d: String): Boolean {
            var sum = 0
            d.reversed().forEachIndexed { i, c ->
                var n = c - '0'
                if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
                sum += n
            }
            return sum % 10 == 0
        }
    }
}

data class ReportPayload(
    val kind: ReportKind,
    val appVersion: String,
    val appCode: Int,
    val createdMs: Long,
    val device: DeviceClaims,
    /** Filled in by the signer from the actual Keystore key. */
    val keyId: String = "",
    /** 16 bytes base64url; required for MEETUP, null for LISTING. */
    val nonce: String? = null,
    val tests: List<TestResult>,
    val imei: ImeiClaim? = null,
    val photos: List<PhotoRef> = emptyList(),
    val notes: String? = null,
) {
    init {
        require((kind == ReportKind.MEETUP) == (nonce != null)) { "nonce is required for meetup and forbidden for listing" }
        nonce?.let { require(Encoding.fromB64url(it).size == 16) { "nonce must be 16 bytes" } }
        require(photos.size <= 6) { "At most 6 photos" }
        notes?.let { require(it.length <= 280) { "notes too long" } }
        require(tests.map { it.id }.toSet().size == tests.size) { "Duplicate test ids" }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("fmt", FORMAT)
        put("v", VERSION)
        put("kind", kind.wire)
        put("app", buildJsonObject { put("ver", appVersion); put("code", appCode) })
        put("created", createdMs)
        put("device", buildJsonObject {
            put("brand", device.brand); put("model", device.model); put("mfr", device.manufacturer)
            put("sdk", device.sdk); put("patch", device.patch)
        })
        put("keyId", keyId)
        put("nonce", nonce?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tests", buildJsonArray {
            tests.forEach { t ->
                add(buildJsonObject {
                    put("id", t.id); put("r", t.outcome.name); put("by", t.by.wire)
                    if (t.measurements.isNotEmpty()) {
                        put("m", buildJsonObject { t.measurements.forEach { (k, v) -> put(k, v) } })
                    }
                })
            }
        })
        put("imei", imei?.let { buildJsonObject { put("value", it.value); put("src", it.source) } } ?: JsonNull)
        put("photos", buildJsonArray {
            photos.forEach { p -> add(buildJsonObject { put("kind", p.kind); put("sha256", p.sha256Hex); put("t", p.takenAtMs) }) }
        })
        put("notes", notes?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        const val FORMAT = "pp.report"
        const val VERSION = 1

        fun fromJson(o: JsonObject): ReportPayload {
            require(o.str("fmt") == FORMAT) { "Not a PhoneProof report" }
            val v = o.getValue("v").jsonPrimitive.int
            require(v == VERSION) { "Unsupported report version $v" }
            val app = o.getValue("app").jsonObject
            val dev = o.getValue("device").jsonObject
            return ReportPayload(
                kind = ReportKind.of(o.str("kind")),
                appVersion = app.str("ver"),
                appCode = app.getValue("code").jsonPrimitive.int,
                createdMs = o.getValue("created").jsonPrimitive.long,
                device = DeviceClaims(dev.str("brand"), dev.str("model"), dev.str("mfr"),
                    dev.getValue("sdk").jsonPrimitive.int, dev.str("patch")),
                keyId = o.str("keyId"),
                nonce = o.strOrNull("nonce"),
                tests = o.getValue("tests").jsonArray.map { e ->
                    val t = e.jsonObject
                    TestResult(
                        id = t.str("id"),
                        outcome = TestOutcome.valueOf(t.str("r")),
                        by = DecidedBy.of(t.str("by")),
                        measurements = (t["m"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.long } ?: emptyMap(),
                    )
                },
                imei = (o["imei"] as? JsonObject)?.let { ImeiClaim(it.str("value"), it.str("src")) },
                photos = o.getValue("photos").jsonArray.map { e ->
                    val p = e.jsonObject
                    PhotoRef(p.str("kind"), p.str("sha256"), p.getValue("t").jsonPrimitive.long)
                },
                notes = o.strOrNull("notes"),
            )
        }
    }
}

/** What travels over the wire and gets stored. Only [payload] is covered by [sig]. */
data class ReportEnvelope(
    val payload: JsonObject,
    /** base64url DER ECDSA-P256-SHA256 over CanonicalJson(payload). */
    val sig: String,
    /** base64 DER certificates, leaf first, root last. */
    val chain: List<String>,
    /** Worker countersignature, added server-side; null offline / in meet-up mode. */
    val cs: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("payload", payload)
        put("sig", sig)
        put("chain", JsonArray(chain.map { JsonPrimitive(it) }))
        if (cs != null) put("cs", cs)
    }

    fun encode(): String = CanonicalJson.encode(toJson())

    companion object {
        const val MAX_BYTES = 16 * 1024
        private val json = Json { ignoreUnknownKeys = false }

        fun decode(text: String): ReportEnvelope {
            require(text.length <= MAX_BYTES) { "Report too large" }
            val o = json.parseToJsonElement(text).jsonObject
            require(o.getValue("v").jsonPrimitive.int == 1) { "Unsupported envelope version" }
            return ReportEnvelope(
                payload = o.getValue("payload").jsonObject,
                sig = o.str("sig"),
                chain = o.getValue("chain").jsonArray.map { it.jsonPrimitive.content },
                cs = o["cs"] as? JsonObject,
            )
        }
    }
}

internal fun JsonObject.str(k: String): String {
    val p = getValue(k).jsonPrimitive
    require(p.isString) { "$k must be a string" }
    return p.content
}

internal fun JsonObject.strOrNull(k: String): String? {
    val e: JsonElement = this[k] ?: return null
    if (e is JsonNull) return null
    val p = e.jsonPrimitive
    require(p.isString) { "$k must be a string or null" }
    return p.content
}
