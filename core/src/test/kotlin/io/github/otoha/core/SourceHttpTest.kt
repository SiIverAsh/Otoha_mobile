package io.github.otoha.core

import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SourceHttpTest {
    private lateinit var server: MockWebServer
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun requestsCarryTheSelectedSourceHeaders() = runBlocking {
        val http = SourceHttp()
        for (source in SourceId.entries) {
            server.enqueue(MockResponse().setBody("ok"))
            assertEquals("ok", http.get(server.url("/catalog").toString(), source))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            val expected = source.requestHeaders()
            assertEquals(expected["Referer"], request.getHeader("Referer"))
            assertEquals(expected["Origin"], request.getHeader("Origin"))
            assertEquals(expected["User-Agent"], request.getHeader("User-Agent"))
        }
    }

    @Test fun httpErrorPreservesStatusAndRetryGuidance() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("rate limited"))
        val error = errorFrom(SourceHttp())
        assertEquals(429, error.status)
        assertEquals("120", error.retryAfter)
        assertFalse(error.challenge)
    }

    @Test fun challengeIsDistinctFromAnEmptyCatalogOrGenericHttpError() = runBlocking {
        val responses = listOf(
            MockResponse().setHeader("CF-Mitigated", "challenge").setResponseCode(403),
            MockResponse().setBody("<html><title>Just a moment...</title></html>"),
            MockResponse().setResponseCode(403).setBody("<script src='/cdn-cgi/challenge-platform/example'></script>"),
        )
        for (response in responses) {
            server.enqueue(response)
            assertTrue(errorFrom(SourceHttp()).challenge)
        }
    }

    @Test fun declaredAndChunkedOversizeResponsesAreRejected() = runBlocking {
        val oversized = Buffer().write(ByteArray(SourceHttp.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() })
        server.enqueue(MockResponse().setBody(oversized.clone()))
        assertTrue(errorFrom(SourceHttp()).message.orEmpty().contains("过大"))
        server.enqueue(MockResponse().setChunkedBody(oversized, 8192))
        assertTrue(errorFrom(SourceHttp()).message.orEmpty().contains("过大"))
    }

    @Test fun cancellingCoroutineCancelsTheUnderlyingOkHttpCall() = runBlocking {
        val failed = CompletableDeferred<Boolean>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.complete(call.isCanceled()) }
        }).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { SourceHttp(client).get(server.url("/slow").toString(), SourceId.ASMR_ONE) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertTrue(withTimeout(2000) { failed.await() })
    }

    @Test fun perSourceGateSpacesRequestStartsAndWaitingRequestsAreCancellable() = runBlocking {
        val starts = mutableListOf<Long>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { starts += System.nanoTime() }
        }).build()
        val http = SourceHttp(client)
        server.enqueue(MockResponse().setBody("first"))
        server.enqueue(MockResponse().setBody("second"))
        assertEquals("first", http.get(server.url("/first").toString(), SourceId.ASMR_ONE))
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { http.get(server.url("/cancelled").toString(), SourceId.ASMR_ONE) }
        // The request is still at the source gate: cancellation must not enqueue it later.
        cancelled.cancelAndJoin()
        assertEquals("second", http.get(server.url("/second").toString(), SourceId.ASMR_ONE))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(starts[1] - starts[0]) >= 480)
        assertEquals(2, server.requestCount)
        assertEquals("/first", server.takeRequest().path)
        assertEquals("/second", server.takeRequest().path)
    }

    @Test fun subtitlesDecodeBomUnicodeAndLegacyEncodings() = runBlocking {
        val http = SourceHttp()
        val samples = listOf(
            "[00:01.00]中文 & 日本語".toByteArray(Charsets.UTF_8) to "[00:01.00]中文 & 日本語",
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "[00:01.00]中文".toByteArray(Charsets.UTF_8) to "[00:01.00]中文",
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "[00:01.00]中文".toByteArray(Charsets.UTF_16LE) to "[00:01.00]中文",
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) + "[00:01.00]日本語".toByteArray(Charsets.UTF_16BE) to "[00:01.00]日本語",
            "[00:05.50]お帰りなさい、今日も一日お疲れ様でした\n[00:12.00]ゆっくり休んでくださいね".toByteArray(Charset.forName("Shift_JIS")) to "[00:05.50]お帰りなさい、今日も一日お疲れ様でした\n[00:12.00]ゆっくり休んでくださいね",
            "1\n00:00:02,250 --> 00:00:06,500\n欢迎回来，今天辛苦啦\n\n2\n00:00:07,000 --> 00:00:10,000\n给你掏耳朵放松一下吧".toByteArray(Charset.forName("GB18030")) to "1\n00:00:02,250 --> 00:00:06,500\n欢迎回来，今天辛苦啦\n\n2\n00:00:07,000 --> 00:00:10,000\n给你掏耳朵放松一下吧",
        )
        for ((bytes, expected) in samples) assertEquals(expected, SourceHttp.decodeSubtitle(bytes))
        server.enqueue(MockResponse().setBody(Buffer().write(samples[2].first)))
        assertEquals(samples[2].second, http.getSubtitle(server.url("/subtitle.lrc").toString(), SourceId.ASMR_ONE))
        assertEquals("https://asmr.one/", server.takeRequest().getHeader("Referer"))
    }

    @Test fun webpageDecodingDoesNotGuessLegacySubtitleEncodings() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=Shift_JIS")
            .setBody(Buffer().write("<title>ささやき</title>".toByteArray(Charset.forName("Shift_JIS")))))
        assertEquals("<title>ささやき</title>", SourceHttp().get(server.url("/page").toString(), SourceId.ASMR18))
    }

    private suspend fun errorFrom(http: SourceHttp): SourceException {
        try {
            http.get(server.url("/error").toString(), SourceId.ASMR18)
            fail("Expected a source error")
        } catch (expected: SourceException) { return expected }
        error("unreachable")
    }
}
