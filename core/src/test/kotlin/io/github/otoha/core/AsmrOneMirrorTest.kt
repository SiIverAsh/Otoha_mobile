package io.github.otoha.core

import java.net.URI
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AsmrOneMirrorTest {
    private val mirrors = listOf("https://first.test", "https://second.test")
    private val catalog = """{"works":[],"pagination":{"totalCount":0}}"""

    @Test fun latestWorkingMirrorIsTriedFirstAndCanChangeAfterFailover() = runBlocking {
        val hosts = mutableListOf<String>()
        var working = "second.test"
        val client = AsmrOneClient(mirrors = mirrors, request = { url, _ ->
            val host = URI(url).host
            hosts += host
            if (host == working) catalog else throw SourceException("unavailable", 503)
        })

        client.browse(1)
        client.search("test")
        working = "first.test"
        client.browse(2)
        client.search("another")

        assertEquals(
            listOf("first.test", "second.test", "second.test", "second.test", "first.test", "first.test"),
            hosts,
        )
    }

    @Test fun slowMirrorIsCancelledSoNextMirrorGetsATurn() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = AsmrOneClient(
            mirrors, requestTimeoutMs = 2_000, mirrorTimeoutMs = 80,
            request = { url, _ ->
                val host = URI(url).host
                hosts += host
                if (host == "first.test") awaitCancellation() else catalog
            },
        )

        assertEquals(1, client.browse(1).page)
        assertEquals(listOf("first.test", "second.test"), hosts)
    }

    @Test fun overallDeadlineStopsMirrorFallbackAndReportsARetryableError() {
        val hosts = mutableListOf<String>()
        val client = AsmrOneClient(
            mirrors + "https://unused.test", requestTimeoutMs = 500, mirrorTimeoutMs = 2_000,
            request = { url, _ ->
                val host = URI(url).host
                hosts += host
                if (host == "first.test") throw SourceException("unavailable", 503)
                awaitCancellation()
            },
        )

        val error = assertThrows(SourceException::class.java) { runBlocking { client.browse(1) } }
        assertEquals("ASMR.one 请求超时，请重试", error.message)
        assertEquals(listOf("first.test", "second.test"), hosts)
    }

    @Test fun workInfoAndTracksShareOneDeadlineWithACustomMirror() {
        val paths = mutableListOf<String>()
        val client = AsmrOneClient(
            mirrors.take(1), requestTimeoutMs = 600, mirrorTimeoutMs = 60,
            request = { url, _ ->
                val path = URI(url).path
                paths += path
                delay(400)
                if (path.startsWith("/api/workInfo/")) """{"id":123456,"title":"Work"}""" else "[]"
            },
        )

        val error = assertThrows(SourceException::class.java) {
            runBlocking { client.detail(WorkRef(SourceId.ASMR_ONE, "123456")) }
        }
        assertEquals("ASMR.one 请求超时，请重试", error.message)
        assertEquals(listOf("/api/workInfo/123456", "/api/tracks/123456"), paths)
    }
}
