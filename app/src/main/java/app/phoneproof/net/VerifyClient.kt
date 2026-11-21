package app.phoneproof.net

import android.content.Context
import app.phoneproof.BuildConfig
import app.phoneproof.core.ReportEnvelope
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Talks to the PhoneProof verify server (the Cloudflare Worker in /worker).
 * Every call is optional: with VERIFY_BASE_URL empty the app works offline.
 */
class VerifyClient(private val context: Context) {

    val enabled: Boolean get() = BuildConfig.VERIFY_BASE_URL.isNotBlank()
    private val base get() = BuildConfig.VERIFY_BASE_URL.trimEnd('/')

    data class Uploaded(val id: String, val url: String, val envelope: ReportEnvelope)

    /** Upload a signed report. Returns the server copy (with countersign) and its link. */
    suspend fun upload(envelope: ReportEnvelope, requestHash: String): Result<Uploaded> = withContext(Dispatchers.IO) {
        runCatching {
            check(enabled) { "Verify server not configured" }
            val token = Integrity.token(context, requestHash)
            val body = buildJsonObject {
                put("envelope", envelope.toJson())
                put("integrity", token?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            val resp = http("POST", "$base/api/reports", body.toString())
            val o = Json.parseToJsonElement(resp).jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            val cs = o["cs"] as? JsonObject
            Uploaded(id, o["url"]?.jsonPrimitive?.content ?: "$base/r/$id", envelope.copy(cs = cs))
        }
    }

    suspend fun fetch(id: String): Result<ReportEnvelope> = withContext(Dispatchers.IO) {
        runCatching { ReportEnvelope.decode(http("GET", "$base/api/reports/$id", null)) }
    }

    /** Revoked attestation serials, mirrored daily by the server from Google's status list. */
    suspend fun revokedSerials(): Set<String> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptySet()
        runCatching {
            Json.parseToJsonElement(http("GET", "$base/api/revoked", null)).jsonObject.getValue("serials")
                .let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonPrimitive.content }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun http(method: String, url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("Server said $code: ${text.take(200)}")
        return text
    }
}

/** Play Integrity standard requests. Disabled (returns null) until a Cloud project number is set. */
object Integrity {
    @Volatile private var provider: StandardIntegrityTokenProvider? = null

    suspend fun token(context: Context, requestHash: String): String? {
        val project = BuildConfig.INTEGRITY_PROJECT_NUMBER
        if (project == 0L) return null
        return withTimeoutOrNull(10_000) {
            runCatching {
                val p = provider ?: IntegrityManagerFactory.createStandard(context.applicationContext)
                    .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(project).build())
                    .await().also { provider = it }
                p.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await().token()
            }.getOrNull()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWith(Result.failure(it)) }
}
