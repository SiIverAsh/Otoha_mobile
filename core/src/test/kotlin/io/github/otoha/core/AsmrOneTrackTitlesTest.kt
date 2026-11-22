package io.github.otoha.core

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class AsmrOneTrackTitlesTest {
    private val ref = WorkRef(SourceId.ASMR_ONE, "362053")

    @Test
    fun detailRenamesAllFourVersionsUsingThePublishedOrder() = runBlocking {
        val titles = listOf("第一話", "第二話", "第三話", "第四話", "第五話", "第六話", "エピローグ")
        val times = listOf("5:10", "19:55", "21:02", "14:47", "15:35", "10:30", "4:47")
        // The desktop library recorded four seven-track versions with slightly different timings.
        val withEffects = listOf(
            version("mp3", "_SEあり", listOf(310.442, 1195.370, 1262.433, 888.011, 935.741, 630.524, 287.582)),
            version("wav", "_SEあり", listOf(310.357, 1195.200, 1262.240, 887.872, 935.584, 630.400, 287.499)),
        )
        val withoutEffects = listOf(
            version("mp3", "(No SE)", listOf(308.900, 1195.370, 1262.590, 888.141, 939.346, 632.117, 287.582)),
            version("wav", "(No SE)", listOf(308.800, 1195.200, 1262.400, 888.000, 939.200, 632.000, 287.499)),
        )
        val body = "[${folder("SEあり", withEffects)},${folder("SEなし", withoutEffects)}]"
        val page = titles.indices.joinToString("<br>", """<div class="c-productContent__text">""", "</div>") {
            "【トラック${it + 1}】${titles[it]}(SEあり/${times[it]})"
        }
        var requests = 0
        val detail = client(body) { requests++; page }.detail(ref)
        val original = AsmrOneParser.tracks(body, detail.summary.ref)
        val expected = original.groupBy(AudioTrack::directory).values.flatMap { tracks ->
            tracks.zip(titles.reversed()) { track, title -> track.copy(title = title) }
        }

        assertEquals(1, requests)
        assertEquals(28, detail.tracks.size)
        assertEquals(expected, detail.tracks)
        assertEquals(Subtitle("https://media.test/subtitle", "srt"), detail.tracks.last().subtitle)
    }

    @Test
    fun detailChecksEveryDurationAndOnlyRenamesGenericTracksInMatchingFormats() = runBlocking {
        val body = listOf(
            audio("０１_Opening.wav", 120.25),
            audio("０２(No SE).wav", 341.2),
            audio("Track 01.mp3", 100.0),
            audio("Track 02.mp3", 340.0),
        ).joinToString(prefix = "[", postfix = "]")
        val detail = client(body) { twoTracks }.detail(ref)

        assertEquals(
            listOf("０１_Opening.wav", "Ending", "Track 01.mp3", "Track 02.mp3"),
            detail.tracks.map(AudioTrack::title),
        )
    }

    @Test
    fun detailSkipsLookupWhenOrdinalsAreIncompleteOrDuplicated() = runBlocking {
        val cases = listOf(
            listOf(audio("Track 01.mp3", 120.0), audio("Track 03.mp3", 340.0)),
            listOf(audio("Track 01.mp3", 120.0), audio("01_SEなし.mp3", 340.0)),
            listOf(audio("Track 01.mp3", 0.0), audio("Track 02.mp3", 340.0)),
        )
        for (tracks in cases) {
            val body = tracks.joinToString(prefix = "[", postfix = "]")
            val detail = client(body) { error("DLsite must not be requested for an incomplete group") }.detail(ref)
            assertEquals(AsmrOneParser.tracks(body, detail.summary.ref), detail.tracks)
        }
    }

    @Test
    fun detailKeepsPlayableTracksWhenDlsiteIsUnavailable() = runBlocking {
        val body = listOf(audio("Track 00.mp3", 120.0), audio("Track 01.mp3", 340.0))
            .joinToString(prefix = "[", postfix = "]")
        var requests = 0
        val detail = client(body) { requests++; throw IOException("offline") }.detail(ref)

        assertEquals(1, requests)
        assertEquals(AsmrOneParser.tracks(body, detail.summary.ref), detail.tracks)
    }

    private fun client(tracks: String, trackPage: () -> String): AsmrOneClient {
        val http = SourceHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "www.dlsite.com")
            check(request.url.encodedPath.endsWith("/RJ362053.html"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(trackPage().toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }.build())
        return AsmrOneClient(http, mirrors = listOf("https://api.test"), request = { url, _ ->
            when {
                "/api/workInfo/" in url -> """{"id":362053,"source_id":"RJ362053","title":"Work"}"""
                "/api/tracks/" in url -> tracks
                else -> error("Unexpected URL $url")
            }
        })
    }

    private fun audio(title: String, duration: Double): String =
        """{"type":"audio","title":"$title","duration":$duration,"mediaStreamUrl":"https://media.test/audio"}"""

    private fun version(format: String, suffix: String, durations: List<Double>): String {
        val tracks = durations.indices.reversed().map { audio("0${it + 1}$suffix.$format", durations[it]) }
        val subtitle = """{"type":"text","title":"01$suffix.srt","mediaDownloadUrl":"https://media.test/subtitle"}"""
        return folder(format, tracks + subtitle)
    }

    private fun folder(name: String, children: List<String>): String =
        children.joinToString(prefix = """{"type":"folder","title":"$name","children":[""", postfix = "]}")

    private val twoTracks = """
        <div class="c-productContent__text">1 Opening (2:00)<br>2 Ending (5:40)</div>
    """.trimIndent()
}
