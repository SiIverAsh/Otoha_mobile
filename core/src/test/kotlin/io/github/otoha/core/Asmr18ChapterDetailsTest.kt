package io.github.otoha.core

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Asmr18ChapterDetailsTest {
    @Test
    fun publishedTracksSplitEachVerifiedVersionIntoItsOwnQueue() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val detail = client(requests).detail(Asmr18Parser.ref(WORK_URL))
        val versions = detail.tracks.groupBy(AudioTrack::directory).values.toList()

        assertEquals(2, versions.size)
        assertNotEquals(versions[0].first().url, versions[1].first().url)
        for (version in versions) {
            assertEquals(6, version.size)
            assertEquals(
                listOf(0L, 774_000L, 1_550_000L, 2_400_000L, 3_300_000L, 4_200_000L),
                version.map(AudioTrack::clipStartMs),
            )
            assertEquals(version, detail.queueFor(version[1]))
        }
        assertEquals(setOf(WORK_URL, DLSITE_URL, BGM_URL, NO_BGM_URL), requests.toSet())
        assertEquals(4, requests.size)
    }

    @Test
    fun keepsAnUnverifiedVersionAsAnEntireStream() = runBlocking {
        val detail = client(noBgmPlaylist = "5120.000").detail(Asmr18Parser.ref(WORK_URL))

        assertEquals(6, detail.tracks.count { it.url == BGM_URL && it.clipStartMs != null })
        val unverified = detail.tracks.single { it.url == NO_BGM_URL }
        assertNull(unverified.clipStartMs)
        assertEquals(listOf(unverified), detail.queueFor(unverified))
    }

    @Test
    fun playlistFailureKeepsThatVersionPlayableAndOtherVersionSplits() = runBlocking {
        val detail = client(noBgmPlaylist = null).detail(Asmr18Parser.ref(WORK_URL))

        assertEquals(6, detail.tracks.count { it.url == BGM_URL && it.clipStartMs != null })
        assertNull(detail.tracks.single { it.url == NO_BGM_URL }.clipStartMs)
    }

    @Test
    fun nativeChaptersKeepTheirBoundariesAndSkipOtherRequests() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val nativePage = requireNotNull(javaClass.getResource("/asmr18-work.html")).readText()
        val detail = client(requests, workPage = nativePage).detail(Asmr18Parser.ref(WORK_URL))

        assertEquals(
            listOf(0L, 1_825_000L, 3_744_000L, 5_750_000L, 9_069_000L),
            detail.tracks.map(AudioTrack::clipStartMs),
        )
        assertEquals(listOf(WORK_URL), requests.toList())
    }

    @Test
    fun missingTimedTrackListKeepsBothVersionsAndSkipsPlaylistRequests() = runBlocking {
        val requests = ConcurrentLinkedQueue<String>()
        val detail = client(requests, trackList = "<h1>Example album</h1>").detail(Asmr18Parser.ref(WORK_URL))

        assertEquals(listOf(null, null), detail.tracks.map(AudioTrack::clipStartMs))
        assertEquals(listOf(WORK_URL, DLSITE_URL), requests.toList())
    }

    private fun client(
        requests: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
        noBgmPlaylist: String? = "5164.440",
        workPage: String = WORK_PAGE,
        trackList: String = TRACK_LIST,
    ): Asmr18Client {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            requests += url
            val body = when (url) {
                WORK_URL -> workPage
                DLSITE_URL -> trackList
                BGM_URL -> playlist("5164.440")
                NO_BGM_URL -> noBgmPlaylist?.let(::playlist)
                else -> error("Unexpected request $url")
            }
            val status = if (body == null) 503 else 200
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Test response").body(body.orEmpty().toResponseBody()).build()
        }.build()
        return Asmr18Client(SourceHttp(transport))
    }

    private fun playlist(duration: String): String = """
        #EXTM3U
        #EXTINF:$duration,
        segment.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private companion object {
        const val WORK_URL = "https://asmr18.fans/boys/rj01175528/"
        const val DLSITE_URL = "https://www.dlsite.com/maniax/work/=/product_id/RJ01175528.html"
        const val BGM_URL = "https://cdn3.cloudintech.net/file/RJ01175528/RJ01175528.m3u8"
        const val NO_BGM_URL = "https://cdn3.cloudintech.net/file/RJ01175528/RJ01175528+no+bgm.m3u8"
        val WORK_PAGE = """
            <h1>Example album</h1><label for="file1">RJ01175528</label><label for="file2">RJ01175528 no bgm</label>
            <div class="tab_content" id="file1_content"><script>const src='$BGM_URL';</script></div>
            <div class="tab_content" id="file2_content"><script>const src='$NO_BGM_URL';</script></div>
        """.trimIndent()
        val TRACK_LIST = """
            <div class="c-productContent__text">
            トラック1『Chapter One』(12:54)<br>
            トラック2『Chapter Two』(12:56)<br>
            トラック3『Chapter Three』(14:10)<br>
            トラック4『Chapter Four』(15:00)<br>
            トラック5『Chapter Five』(15:00)<br>
            トラック6『Chapter Six』(16:02)
            </div>
        """.trimIndent()
    }
}
