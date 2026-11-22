package io.github.otoha.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DlsiteTrackListParserTest {
    @Test fun readsBracketedTrackTitlesWithEffectTimingAnnotations() {
        val html = """
            <div class="c-productContent__text">
            【トラック1】第一話(SEあり/5:10)<br>
            第一話の紹介文です。<br>
            【トラック2】第二話(SEあり/19:55)<br>
            【トラック3】第三話(SEあり/21:02)<br>
            【トラック4】第四話(SEあり/14:47)<br>
            【トラック5】第五話(SEあり/15:35)<br>
            【トラック6】第六話(SEあり/10:30)<br>
            【トラック7】エピローグ(SEなし/4:47)
            </div>
        """.trimIndent()

        assertEquals(
            listOf(
                DlsiteTrack("第一話", 310_000L),
                DlsiteTrack("第二話", 1_195_000L),
                DlsiteTrack("第三話", 1_262_000L),
                DlsiteTrack("第四話", 887_000L),
                DlsiteTrack("第五話", 935_000L),
                DlsiteTrack("第六話", 630_000L),
                DlsiteTrack("エピローグ", 287_000L),
            ),
            DlsiteTrackListParser.parse(html),
        )
    }

    @Test fun prefersTheStructuredTableAndSkipsItsTrailingTotal() {
        val html = table("冒頭 &amp; ご案内" to "５：０４", "本編" to "1:02:03", "総収録時間：" to "1:07:07") +
            description("1. 初め 1:04", "2. 続き 2:06")

        assertEquals(
            listOf(DlsiteTrack("冒頭 & ご案内", 304_000L), DlsiteTrack("本編", 3_723_000L)),
            DlsiteTrackListParser.parse(html),
        )
    }

    @Test fun readsZeroBasedFullWidthAndMixedTrackHeadings() {
        val html = description(
            "★track00 はじめに 0:08",
            "Track 1 ご案内・・ (1:02)",
            "トラック２　<b>出発</b>（０３：０５）",
            "３エンディング(5:06)",
        )
        assertEquals(
            listOf(
                DlsiteTrack("はじめに", 8_000L),
                DlsiteTrack("ご案内", 62_000L),
                DlsiteTrack("出発", 185_000L),
                DlsiteTrack("エンディング", 306_000L),
            ),
            DlsiteTrackListParser.parse(html),
        )
    }

    @Test fun rejectsMissingAndAmbiguousTrackDurationsOrLists() {
        val validTable = table("Intro" to "1:04", "Main" to "2:08")
        val htmlByCase = mapOf(
            "competing tables" to (validTable + validTable),
            "competing descriptions" to (
                description("1. Intro 1:04", "2. Main 2:08") + description("1. Opening 1:04", "2. Ending 2:08")
            ),
            "missing track" to description("1. Intro 1:04", "3. Main 2:08"),
            "zero duration" to table("Intro" to "1:04", "Main" to "0:00"),
            "invalid duration" to table("Intro" to "1:04", "Main" to "1:70:00"),
            "missing duration" to description("1. Intro 1:04", "2. Main", "3. Closing 2:08"),
            "approximate duration" to description("1. Intro 1:04", "2. Main およそ2:08"),
            "English approximate duration" to description("Track 1 Intro 1:04", "Track 2 Main approx. 2:08"),
            "duration range" to description("1. Intro 1:04", "2. Main 2:08～2:10"),
            "different tracks for each effect" to description(
                "Track 1 Intro (1:04)", "Track 2 Main (SEあり/2:08) / (SEなし/2:10)",
            ),
        )
        for ((scenario, html) in htmlByCase) {
            assertTrue(scenario, DlsiteTrackListParser.parse(html).isEmpty())
        }
    }

    private fun description(vararg lines: String): String =
        lines.joinToString("<br>", prefix = """<div class="c-productContent__text">""", postfix = "</div>")

    private fun table(vararg rows: Pair<String, String>): String = rows.joinToString(
        separator = "",
        prefix = """<ul class="c-productContent__trackList">""",
        postfix = "</ul>",
    ) { (title, duration) ->
        """
        <li class="c-productContent__trackListItem">
          <div class="c-productContent__trackItemTitle">$title</div>
          <div class="c-productContent__trackItemTime">$duration</div>
        </li>
        """.trimIndent()
    }
}
