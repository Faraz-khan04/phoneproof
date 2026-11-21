package app.phoneproof.data

import android.content.Context
import app.phoneproof.core.CanonicalJson
import app.phoneproof.core.ReportEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * Saved reports, one JSON file each in app-private storage (never backed up, see
 * allowBackup=false). Holds the signed envelope as produced, the measured detail lines,
 * and the server link once uploaded. Reports are re-verified every time they are opened.
 */
class ReportStore(context: Context) {

    data class Saved(
        val id: String,
        val createdMs: Long,
        val envelope: ReportEnvelope,
        val details: Map<String, String>,
        val shareUrl: String?,
        val meetup: Boolean,
    )

    private val dir = File(context.filesDir, "reports").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun save(s: Saved) {
        val o = buildJsonObject {
            put("id", s.id); put("created", s.createdMs); put("meetup", s.meetup)
            put("envelope", s.envelope.toJson())
            put("details", JsonObject(s.details.mapValues { JsonPrimitive(it.value) }))
            s.shareUrl?.let { put("url", it) }
        }
        val f = File(dir, "${s.id}.json")
        val tmp = File(dir, "${s.id}.json.tmp")
        tmp.writeText(CanonicalJson.encode(o)); tmp.renameTo(f)
    }

    fun list(): List<Saved> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { read(it) }.getOrNull() }
            .sortedByDescending { it.createdMs }

    fun get(id: String): Saved? = File(dir, "$id.json").takeIf { it.exists() }?.let { runCatching { read(it) }.getOrNull() }

    fun delete(id: String) { File(dir, "$id.json").delete() }

    private fun read(f: File): Saved {
        val o = json.parseToJsonElement(f.readText()).jsonObject
        return Saved(
            id = o.getValue("id").jsonPrimitive.content,
            createdMs = o.getValue("created").jsonPrimitive.long,
            envelope = ReportEnvelope.decode(CanonicalJson.encode(o.getValue("envelope"))),
            details = (o["details"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
            shareUrl = o["url"]?.jsonPrimitive?.content,
            meetup = o["meetup"]?.jsonPrimitive?.content == "true",
        )
    }
}
