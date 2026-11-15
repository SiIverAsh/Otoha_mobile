package io.github.otoha.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.system.exitProcess

/** Optional live-network probe. Never invoked by the offline test suite. */
fun main(args: Array<String>) {
    if (args.singleOrNull() == "filters") { verifyRemoteFilters(); return }
    if (args.singleOrNull() == "subtitles") { verifyRemoteSubtitles(); return }
    if (args.singleOrNull() in listOf("--help", "-h")) {
        println("Usage: ./gradlew :core:run --args='one|eighteen|both [work-id-or-page-url]' (default: both)")
        println("Examples: --args='one 243448'; --args='eighteen https://asmr18.fans/allages/rj123456/'")
        println("Checks catalog -> detail (at most five works) -> bounded media samples; does not verify Android playback.")
        return
    }
    if (args.size > 2 || (args.size == 2 && args[0] == "both")) {
        System.err.println("Usage: :core:run --args='one|eighteen|both [work-id-or-page-url]'"); exitProcess(2)
    }
    val selection = when (args.firstOrNull() ?: "both") {
        "both" -> listOf(SourceId.ASMR_ONE, SourceId.ASMR18)
        "one" -> listOf(SourceId.ASMR_ONE)
        "eighteen" -> listOf(SourceId.ASMR18)
        else -> { System.err.println("Usage: :core:run --args='one|eighteen|both [work-id-or-page-url]'"); exitProcess(2) }
    }
    val explicitRef = args.getOrNull(1)?.let { raw ->
        try {
            if (selection.single() == SourceId.ASMR18) Asmr18Parser.ref(raw)
            else {
                val id = AsmrOneParser.canonicalWorkNumber(raw).ifBlank { raw.trim() }
                require(id.toIntOrNull()?.let { it > 0 } == true || AsmrOneParser.canonicalWorkNumber(id).isNotEmpty())
                WorkRef(SourceId.ASMR_ONE, id)
            }
        } catch (_: IllegalArgumentException) {
            System.err.println("Invalid work ID or ASMR18 page URL"); exitProcess(2)
        }
    }
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false).build()
    val http = SourceHttp(client)
    val sources = mapOf(SourceId.ASMR_ONE to AsmrOneClient(http), SourceId.ASMR18 to Asmr18Client(http))
    val media = ProbeMedia(client)
    var failures = 0
    try {
        runBlocking {
            for (source in selection) {
                try {
                    println("[$source] catalog status=STARTED")
                    val adapter = sources.getValue(source)
                    val page = adapter.browse(1, "allages")
                    println("[$source] catalog status=OK count=${page.works.size} page=${page.page} hasMore=${page.hasMore}")
                    val candidates = explicitRef?.let { listOf(WorkSummary(it, "")) }
                        ?: page.works.sortedBy { if (it.rating == ContentRating.GENERAL) 0 else 1 }.take(5)
                    var playable: WorkDetail? = null
                    for (work in candidates) {
                        println("[$source] detail id=${work.ref.id} status=STARTED")
                        try {
                            val detail = adapter.detail(work.ref)
                            println("[$source] detail id=${detail.summary.ref.id} status=OK tracks=${detail.tracks.size}")
                            if (detail.tracks.isNotEmpty()) { playable = detail; break }
                        } catch (cancel: CancellationException) { throw cancel
                        } catch (error: Exception) {
                            println("[$source] detail id=${work.ref.id} status=FAILED kind=${failureKind(error)}")
                        }
                    }
                    val work = playable ?: throw ProbeFailure("no_playable_work_within_five_candidates")
                    val track = work.tracks.first()
                    println("[$source] media id=${track.id} status=STARTED")
                    withContext(Dispatchers.IO) { media.check(source, track) }
                    println("[$source] result status=PASS scope=bounded_network_chain_only")
                } catch (cancel: CancellationException) { throw cancel
                } catch (error: Exception) {
                    failures++
                    println("[$source] result status=FAILED kind=${failureKind(error)}")
                }
            }
        }
    } finally {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
    if (failures > 0) exitProcess(1)
}

private class ProbeFailure(val kind: String) : IOException(kind)

private fun failureKind(error: Exception): String = when (error) {
    is ProbeFailure -> error.kind
    is SourceException -> if (error.challenge) "access_challenge" else error.status?.let { "http_$it" } ?: "source_response_error"
    is UnknownHostException -> "dns"
    is SocketTimeoutException -> "timeout"
    is SSLException -> "tls"
    is IllegalArgumentException -> "invalid_source_data"
    is IOException -> error.cause?.let { if (it is Exception && it !== error) failureKind(it) else null } ?: "network_or_parse_error"
    else -> "unexpected_${error.javaClass.simpleName}"
}

private class ProbeMedia(private val client: OkHttpClient) {
    private val uriAttribute = Regex("(?:^|,)\\s*URI=\"([^\"]+)\"")
    private val methodAttribute = Regex("(?:^|,)\\s*METHOD=([^,]+)")

    fun check(source: SourceId, track: AudioTrack) {
        val url = checkedUrl(track.url)
        if (!track.isHls && !url.encodedPath.endsWith(".m3u8", true)) {
            sample(source, url, "audio")
            return
        }
        var playlist = manifest(source, url)
        val child = masterChild(playlist.lines)
        if (child != null) {
            playlist = manifest(source, resolve(playlist.url, child))
            if (masterChild(playlist.lines) != null) throw ProbeFailure("nested_hls_master_not_probed")
        }
        if (playlist.lines.none { it.startsWith("#EXTINF:") }) throw ProbeFailure("hls_media_playlist_missing_segments")
        val firstSegmentIndex = playlist.lines.indexOfFirst { it.isNotBlank() && !it.startsWith('#') }
        if (firstSegmentIndex < 0) throw ProbeFailure("hls_media_playlist_missing_segment_uri")

        // Only the key/map governing the first segment is relevant to this bounded sample.
        val prefix = playlist.lines.take(firstSegmentIndex)
        val keyLine = prefix.lastOrNull { it.startsWith("#EXT-X-KEY:") }
        if (keyLine != null) {
            val attributes = keyLine.substringAfter(':')
            val method = methodAttribute.find(attributes)?.groupValues?.get(1).orEmpty()
            if (method != "NONE") {
                val key = uriAttribute.find(attributes)?.groupValues?.get(1) ?: throw ProbeFailure("hls_key_uri_missing")
                val bytes = sample(source, resolve(playlist.url, key), "key")
                if (method == "AES-128" && bytes != 16) throw ProbeFailure("hls_aes128_key_length_invalid")
            }
        }
        prefix.lastOrNull { it.startsWith("#EXT-X-MAP:") }?.let { line ->
            val map = uriAttribute.find(line.substringAfter(':'))?.groupValues?.get(1)
                ?: throw ProbeFailure("hls_init_map_uri_missing")
            sample(source, resolve(playlist.url, map), "init")
        }
        sample(source, resolve(playlist.url, playlist.lines[firstSegmentIndex]), "segment")
    }

    private data class Manifest(val url: HttpUrl, val lines: List<String>)

    private fun manifest(source: SourceId, url: HttpUrl): Manifest {
        client.newCall(request(url, source)).execute().use { response ->
            if (response.header("CF-Mitigated").equals("challenge", true)) throw SourceException("manifest", response.code, challenge = true)
            if (!response.isSuccessful) throw SourceException("manifest", response.code, response.header("Retry-After"))
            val body = response.body ?: throw ProbeFailure("hls_empty_response")
            if (body.contentLength() > MAX_MANIFEST_BYTES) throw ProbeFailure("hls_manifest_too_large")
            val bytes = prefix(body.byteStream(), MAX_MANIFEST_BYTES + 1)
            if (bytes.size > MAX_MANIFEST_BYTES) throw ProbeFailure("hls_manifest_too_large")
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
            if (!text.startsWith("#EXTM3U")) throw ProbeFailure("invalid_hls_manifest")
            println("[$source] manifest status=${response.code} bytes=${bytes.size}")
            return Manifest(response.request.url, text.lines().map(String::trim))
        }
    }

    private fun masterChild(lines: List<String>): String? {
        // Prefer an explicitly advertised audio rendition before a multiplexed variant.
        lines.firstOrNull { it.startsWith("#EXT-X-MEDIA:") && "TYPE=AUDIO" in it && uriAttribute.containsMatchIn(it.substringAfter(':')) }
            ?.let { return uriAttribute.find(it.substringAfter(':'))!!.groupValues[1] }
        val streamIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (streamIndex < 0) return null
        return lines.drop(streamIndex + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
            ?: throw ProbeFailure("hls_master_missing_variant_uri")
    }

    private fun sample(source: SourceId, url: HttpUrl, kind: String): Int {
        client.newCall(request(url, source).newBuilder().header("Range", "bytes=0-1023").build()).execute().use { response ->
            if (response.header("CF-Mitigated").equals("challenge", true)) throw SourceException(kind, response.code, challenge = true)
            if (!response.isSuccessful) throw SourceException(kind, response.code, response.header("Retry-After"))
            val body = response.body ?: throw ProbeFailure("${kind}_empty_response")
            if (body.contentType()?.subtype?.contains("json", true) == true) throw ProbeFailure("${kind}_unexpected_document")
            // Servers may ignore Range. Stop reading at 64 KiB regardless of advertised size.
            val bytes = prefix(body.byteStream(), MAX_SAMPLE_BYTES)
            if (bytes.isEmpty()) throw ProbeFailure("${kind}_empty_body")
            val beginning = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.UTF_8).trimStart().lowercase()
            if (beginning.startsWith("<!doctype html") || beginning.startsWith("<html") || beginning.startsWith("<?xml")) {
                throw ProbeFailure("${kind}_unexpected_document")
            }
            println("[$source] $kind status=${response.code} bytes=${bytes.size} rangeHonored=${response.code == 206}")
            return bytes.size
        }
    }

    private fun request(url: HttpUrl, source: SourceId): Request = Request.Builder().url(url).apply {
        source.requestHeaders().forEach { (name, value) -> header(name, value) }
    }.build()

    private fun checkedUrl(raw: String): HttpUrl = raw.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    } ?: throw ProbeFailure("media_url_requires_https")

    private fun resolve(base: HttpUrl, reference: String): HttpUrl = checkedUrl(
        base.resolve(reference)?.toString() ?: throw ProbeFailure("invalid_hls_resource_uri"),
    )

    private fun prefix(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (output.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_SAMPLE_BYTES = 64 * 1024
    }
}
