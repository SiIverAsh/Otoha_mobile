package io.github.otoha.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AsmrOneParserTest {
    @Test fun catalogUsesSourceIdentityAgeEvidenceAndPagination() {
        val page = AsmrOneParser.catalog("""{
            "works":[
                {"id":1000099,"source_id":"RJ01000099","title":"All ages","age_category_string":"general",
                 "mainCoverUrl":"//images.example/cover.jpg","circle":{"name":"Circle"},
                 "vas":[{"name":"Voice"},{"name":"Voice"},{"name":"N/A"}],
                 "tags":[{"name":"耳かき","i18n":{"zh-cn":{"name":"掏耳"}}}]},
                {"id":2,"title":"Missing classification","nsfw":false},
                {"id":3,"title":"Restricted","age_category_string":"general","nsfw":true},
                {"id":4,"title":"Teen","age_category_string":"r15"}
            ],"pagination":{"currentPage":2,"pageSize":4,"totalCount":9}}
        """.trimIndent(), 2)
        assertTrue(page.hasMore)
        val first = page.works.first()
        assertEquals("1000099", first.ref.id)
        assertEquals("RJ01000099", first.workNumber)
        assertEquals("https://images.example/cover.jpg", first.coverUrl)
        assertEquals(listOf("Voice"), first.actors)
        assertEquals(listOf("掏耳"), first.tags)
        assertEquals(listOf(ContentRating.GENERAL, ContentRating.UNKNOWN, ContentRating.RESTRICTED, ContentRating.RESTRICTED), page.works.map { it.rating })
        assertFalse(AsmrOneParser.catalog("""{"works":[],"pagination":{"currentPage":3,"pageSize":4,"totalCount":9}}""", 3).hasMore)
    }

    @Test fun tracksKeepDirectoryBoundariesSiblingSubtitlesAndStableIds() {
        val ref = WorkRef(SourceId.ASMR_ONE, "123456")
        val tracks = AsmrOneParser.tracks(trackTree("cdn-a"), ref)
        assertEquals(3, tracks.size)
        assertEquals(listOf("SE有り", "wav"), tracks[0].directory)
        assertEquals("https://cdn-a/one.vtt", tracks[0].subtitle?.url)
        assertEquals("vtt", tracks[0].subtitle?.format)
        assertEquals("https://cdn-a/two.lrc", tracks[1].subtitle?.url)
        assertNull(tracks[2].subtitle)
        assertEquals(1500L, tracks[0].durationMs)
        assertEquals("https://cdn-a/low.mp3", tracks[1].url)
        assertEquals(tracks.map { it.id }, AsmrOneParser.tracks(trackTree("cdn-b"), ref).map { it.id })
        assertEquals(listOf(tracks[0], tracks[1]), WorkDetail(WorkSummary(ref, "Work"), tracks).queueFor(tracks[0]))
        assertEquals(listOf(tracks[2]), WorkDetail(WorkSummary(ref, "Work"), tracks).queueFor(tracks[2]))
        assertTrue(tracks.all { it.id.startsWith(ref.key + ":") })
        assertNotEquals(tracks[0].id, AsmrOneParser.tracks(trackTree("cdn-a"), WorkRef(SourceId.ASMR_ONE, "999999"))[0].id)
    }

    @Test fun numericWorkNumbersRetainSixAndEightDigitBranches() {
        assertEquals("RJ000123", AsmrOneParser.work("""{"id":123,"title":"One"}""").workNumber)
        assertEquals("RJ01000099", AsmrOneParser.work("""{"id":1000099,"title":"Two"}""").workNumber)
        assertEquals("BJ123456", AsmrOneParser.canonicalWorkNumber(" bj123456 "))
        assertEquals("", AsmrOneParser.canonicalWorkNumber("RJ1234567"))
        assertEquals("", AsmrOneParser.canonicalWorkNumber("RJ123456789"))
    }

    @Test fun repeatedRemoteHashInDifferentVersionsDoesNotShareAQueueIdentity() {
        val tracks = AsmrOneParser.tracks("""[
            {"type":"folder","title":"SE有り","children":[{"type":"audio","title":"One.mp3","hash":"same","mediaStreamUrl":"https://cdn/one"}]},
            {"type":"folder","title":"SE無し","children":[{"type":"audio","title":"One.mp3","hash":"same","mediaStreamUrl":"https://cdn/two"}]}
        ]""", WorkRef(SourceId.ASMR_ONE, "1"))
        assertEquals(2, tracks.map { it.id }.distinct().size)
    }

    @Test(expected = SourceException::class) fun malformedTrackShapeIsNotAnEmptyWork() {
        AsmrOneParser.tracks("""{"error":"challenge"}""", WorkRef(SourceId.ASMR_ONE, "1"))
    }

    @Test fun mirrorFallbackAndDetailUseReturnedNumericId() = runBlocking {
        val requests = mutableListOf<String>()
        val client = AsmrOneClient(mirrors = listOf("https://down", "https://up"), request = { url, source ->
            assertEquals(SourceId.ASMR_ONE, source)
            requests += url
            when {
                url.startsWith("https://down") -> throw SourceException("offline", 503)
                "/api/workInfo/RJ01000099" in url -> """{"id":1000099,"source_id":"RJ01000099","title":"Work"}"""
                "/api/tracks/1000099" in url -> "[]"
                else -> fail("Unexpected URL $url").let { "" }
            }
        })
        val detail = client.detail(WorkRef(SourceId.ASMR_ONE, "rj01000099"))
        assertEquals("1000099", detail.summary.ref.id)
        assertEquals(listOf(
            "https://down/api/workInfo/RJ01000099", "https://up/api/workInfo/RJ01000099",
            "https://down/api/tracks/1000099", "https://up/api/tracks/1000099",
        ), requests)
    }

    @Test fun missingWorkDoesNotHammerOtherMirrors() = runBlocking {
        val requests = mutableListOf<String>()
        val client = AsmrOneClient(mirrors = listOf("https://one", "https://two"), request = { url, _ ->
            requests += url
            throw SourceException("missing", 404)
        })
        try { client.detail(WorkRef(SourceId.ASMR_ONE, "123")); fail("Expected missing work") }
        catch (expected: SourceException) { assertEquals(404, expected.status) }
        assertEquals(1, requests.size)
    }

    @Test fun cancellationDoesNotTriggerFailover() = runBlocking {
        var count = 0
        val client = AsmrOneClient(request = { _, _ -> count++; throw CancellationException("cancel") })
        try { client.browse(1); fail("Expected cancellation") }
        catch (_: CancellationException) { assertEquals(1, count) }
    }

    @Test fun invalidJsonFallsBackWhileExhaustionRemainsAnError() = runBlocking {
        var count = 0
        val client = AsmrOneClient(mirrors = listOf("https://one", "https://two"), request = { _, _ ->
            count++
            if (count == 1) "<!DOCTYPE html>" else """{"works":[],"pagination":{"totalCount":0}}"""
        })
        assertTrue(client.browse(1).works.isEmpty())
        assertEquals(2, count)
        val failed = AsmrOneClient(mirrors = listOf("https://one"), request = { _, _ -> throw IOException("timeout") })
        try { failed.browse(1); fail("Expected network error") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("所有镜像")) }
    }

    private fun trackTree(host: String) = """[
        {"type":"folder","title":"SE有り","children":[{"type":"folder","title":"wav","children":[
            {"type":"audio","title":"One.wav","hash":"stable-hash","duration":1.5,"mediaStreamUrl":"https://$host/one.wav"},
            {"type":"text","title":"one.VTT","mediaDownloadUrl":"https://$host/one.vtt"},
            {"type":"audio","title":"Two.wav","streamLowQualityUrl":"https://$host/low.mp3"},
            {"type":"text","title":"Two.wav.lrc","mediaStreamUrl":"https://$host/two.lrc"}
        ]}]},
        {"type":"folder","title":"SE無し","children":[
            {"type":"audio","title":"One.wav","mediaDownloadUrl":"https://$host/different.wav"},
            {"type":"text","title":"unrelated.srt","mediaDownloadUrl":"https://$host/unrelated.srt"}
        ]}
    ]""".trimIndent()
}
