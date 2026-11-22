package io.github.otoha.core

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SOURCE_USER_AGENT = "Mozilla/5.0 (compatible; OtohaMobile/0.1)"

class SourceException(
    message: String,
    val status: Int? = null,
    val retryAfter: String? = null,
    val challenge: Boolean = false,
) : IOException(message)

fun SourceId.requestHeaders(): Map<String, String> {
    val origin = if (this == SourceId.ASMR_ONE) "https://asmr.one" else "https://asmr18.fans"
    return mapOf("User-Agent" to SOURCE_USER_AGENT, "Referer" to "$origin/", "Origin" to origin)
}

class SourceHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS).build()) {
    private class Gate {
        val mutex = Mutex()
        var lastStartMs: Long? = null
    }
    private data class Payload(val bytes: ByteArray, val charset: Charset) {
        fun decodeText(): String = bytes.toString(charset).removePrefix("\uFEFF")
    }
    private val gates = SourceId.entries.associateWith { Gate() }
    private val dlsiteGate = Gate()

    suspend fun get(url: String, source: SourceId): String = fetch(url, source).decodeText()

    /** Reads the original Japanese description so track labels match the published list. */
    internal suspend fun getDlsiteTrackPage(workNumber: String): String {
        val url = dlsiteWorkBase.newBuilder().addPathSegment("$workNumber.html").build()
        val request = Request.Builder().url(url)
            .header("User-Agent", SOURCE_USER_AGENT)
            .header("Cookie", "locale=ja-jp")
            .build()
        return fetch(request, dlsiteGate).decodeText()
    }

    /** Subtitle encoding fallback is intentionally separate from JSON/HTML decoding. */
    suspend fun getSubtitle(url: String, source: SourceId): String = decodeSubtitle(fetch(url, source).bytes)

    private suspend fun fetch(url: String, source: SourceId): Payload {
        val request = Request.Builder().url(url).apply {
            source.requestHeaders().forEach { (key, value) -> header(key, value) }
        }.build()
        return fetch(request, gates.getValue(source))
    }

    private suspend fun fetch(request: Request, gate: Gate): Payload {
        val call = client.newCall(request)
        gate.mutex.withLock {
            val now = System.nanoTime() / 1_000_000
            val wait = gate.lastStartMs?.let { 500L - (now - it) } ?: 0L
            if (wait > 0) delay(wait)
            currentCoroutineContext().ensureActive()
            gate.lastStartMs = System.nanoTime() / 1_000_000
        }

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val payload = response.use { readResponse(it) }
                        continuation.resume(payload)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private fun readResponse(response: Response): Payload {
        val retry = response.header("Retry-After")
        if (response.header("CF-Mitigated").equals("challenge", true)) {
            throw SourceException("音源返回访问验证，请稍后重试", response.code, retry, true)
        }
        val body = response.body ?: throw SourceException("音源返回空响应", response.code, retry)
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        if (!response.isSuccessful) {
            val preview = readPrefix(body.byteStream(), 8192).toString(charset).lowercase()
            if (isChallenge(preview)) throw SourceException("音源返回访问验证，请稍后重试", response.code, retry, true)
            throw SourceException("音源 HTTP ${response.code}" +
                (retry?.let { "，Retry-After: $it" } ?: ""), response.code, retry)
        }
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw SourceException("音源响应过大", response.code)
        val bytes = readPrefix(body.byteStream(), MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) throw SourceException("音源响应过大", response.code)
        val preview = bytes.copyOfRange(0, minOf(bytes.size, 8192)).toString(charset).lowercase()
        if (isChallenge(preview)) throw SourceException("音源返回访问验证，请稍后重试", response.code, retry, true)
        return Payload(bytes, charset)
    }

    private fun readPrefix(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isChallenge(preview: String): Boolean =
        "<title>just a moment" in preview || "/cdn-cgi/challenge-platform/" in preview

    companion object {
        internal const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val dlsiteWorkBase = "https://www.dlsite.com/maniax/work/=/product_id/".toHttpUrl()

        // Adapted from Otoha internal/subtitle/parse.go (GPL-3.0). Legacy encodings are
        // necessarily heuristic; BOM and valid UTF-8 always take precedence.
        internal fun decodeSubtitle(bytes: ByteArray): String {
            if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            val content = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
            try {
                return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString()
            } catch (_: CharacterCodingException) {
                // Continue with the two legacy encodings commonly used for the source's subtitles.
            }
            val candidates = listOf("Shift_JIS" to true, "GB18030" to false).mapNotNull { (name, favorKana) ->
                runCatching {
                    val text = content.toString(Charset.forName(name))
                    text to scoreDecoded(text, favorKana)
                }.getOrNull()
            }
            return candidates.maxByOrNull { it.second }?.first ?: content.toString(Charsets.UTF_8)
        }

        private fun scoreDecoded(text: String, favorKana: Boolean): Int {
            var score = 0
            for (char in text) score += when {
                char == '\uFFFD' -> -200
                char in '\u3040'..'\u30FF' -> if (favorKana) 6 else 1
                char in '\u4E00'..'\u9FFF' -> 3
                char in '\uFF61'..'\uFF9F' -> -4
                char == '\n' || char == '\r' || char == '\t' || char in ' '..'~' -> 1
                char < ' ' -> -20
                else -> 0
            }
            return score
        }
    }
}
