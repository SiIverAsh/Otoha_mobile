package io.github.otoha.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.otoha.core.Asmr18Parser
import io.github.otoha.core.AsmrOneParser
import io.github.otoha.core.Chapter
import io.github.otoha.core.ContentRating
import io.github.otoha.core.SubtitleCue
import io.github.otoha.core.SubtitleParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Must run on Android/ART: Robolectric uses the host JDK's more permissive regex engine. */
@RunWith(AndroidJUnit4::class)
class AndroidParserCompatibilityTest {
    @Test
    fun playerRefreshWithoutSubtitlesInitializesParser() {
        // This is called as soon as the media controller connects, even without a selected work.
        assertTrue(SubtitleParser.activeCues(emptyList(), 0).isEmpty())
    }

    @Test
    fun subtitlePatternsAndAssOverrideRemovalWorkOnAndroidIcu() {
        assertEquals(listOf(SubtitleCue(1000, 6000, "hello")),
            SubtitleParser.parse("[00:01.00]hello", "lrc"))
        val timed = listOf(SubtitleCue(1000, 2000, "hello"))
        assertEquals(timed, SubtitleParser.parse("1\n00:00:01,000 --> 00:00:02,000\nhello", "srt"))
        assertEquals(timed, SubtitleParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nhello", "vtt"))
        val ass = "[Events]\nFormat: Start, End, Text\nDialogue: 0:00:01.00,0:00:02.00,{\\b1}hello{\\b0}"
        assertEquals(timed, SubtitleParser.parse(ass, "ass"))
    }

    @Test
    fun sourceParserPatternsInitializeOnAndroidIcu() {
        val summary = AsmrOneParser.work("""{"id":243448,"title":"test","age_category_string":"general"}""")
        assertEquals(ContentRating.GENERAL, summary.rating)
        val work = Asmr18Parser.parseWork("https://asmr18.fans/allages/rj01752865/", """
            <h1>test</h1><div class="tab_content" id="file1_content">
            <script>const src='https://cdn3.cloudintech.net/file/RJ01752865/track.m3u8';</script>
            <div id="chapter"><a data-value="1">chapter</a></div></div>
        """.trimIndent())
        assertEquals(listOf(Chapter("chapter", 1000)), work.tracks.single().chapters)
    }
}
