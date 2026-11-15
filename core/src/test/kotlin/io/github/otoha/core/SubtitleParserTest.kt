package io.github.otoha.core

import org.junit.Assert.*
import org.junit.Test

class SubtitleParserTest {
    @Test fun lrcHandlesOffsetFractionsMultipleTimestampsAndSorting() {
        val cues = SubtitleParser.parse("""
            [ti:Title]
            [00:12.00]休んでください
            [00:05.5][00:08:250]お帰りなさい
            [offset:-500]
        """.trimIndent(), ".LRC")
        assertEquals(listOf(5000L, 7750L, 11500L), cues.map { it.startMs })
        assertEquals(listOf(7750L, 11500L, 16500L), cues.map { it.endMs })
        assertEquals("お帰りなさい", cues[1].text)
        assertEquals(listOf(cues[0]), SubtitleParser.activeCues(cues, 7749))
        assertEquals(listOf(cues[1]), SubtitleParser.activeCues(cues, 7750))
    }

    @Test fun lrcDuplicateTimesStayVisibleAndEmptyLineClearsLyrics() {
        val cues = SubtitleParser.parse("[00:00.00]日本語\n[00:00.00]中文\n[00:03.00]\n[00:05.00]Next", "lrc")
        assertEquals(listOf("日本語", "中文"), SubtitleParser.activeCues(cues, 2000).map { it.text })
        assertTrue(SubtitleParser.activeCues(cues, 3000).isEmpty())
        assertEquals("Next", SubtitleParser.activeCues(cues, 5000).single().text)
    }

    @Test fun srtReadsMultilineTextAndRejectsInvalidIntervals() {
        val cues = SubtitleParser.parse("\uFEFF1\r\n00:00:02,250 --> 00:00:06,500\r\n<b>欢迎回来</b>\r\n今天辛苦啦 &amp; お疲れ様\r\n\r\n2\r\n00:00:07,000 --> 00:00:06,000\r\nInvalid reverse interval\r\n", "srt")
        assertEquals(listOf(SubtitleCue(2250, 6500, "欢迎回来\n今天辛苦啦 & お疲れ様")), cues)
        assertTrue(SubtitleParser.activeCues(cues, 6500).isEmpty())
        assertTrue(SubtitleParser.activeCues(cues, -1).isEmpty())
    }

    @Test fun vttSkipsCommentAndStyleBlocksAndAcceptsCueSettings() {
        val cues = SubtitleParser.parse("""
            WEBVTT

            NOTE ignored comment
            00:01.000 --> 00:03.000
            Must not be shown

            STYLE
            ::cue { color: lime; }

            chapter-1
            00:01.000 --> 00:03.500 align:start position:10%
            <v Narrator><i>ささやき</i> &lt;3

            01:00:00.000 --> 01:00:01.010
            One hour
        """.trimIndent(), "vtt")
        assertEquals(2, cues.size)
        assertEquals(SubtitleCue(1000, 3500, "ささやき <3"), cues.first())
        assertEquals(3_600_000L, cues.last().startMs)
        assertEquals(3_601_010L, cues.last().endMs)
    }

    @Test fun assUsesEventFormatPreservesCommasAndRemovesOverrideTags() {
        val cues = SubtitleParser.parse("""
            [Script Info]
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Not an event
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Comment: 0,0:00:03.20,0:00:08.40,Default,,0,0,0,,Ignored
            Dialogue: 0,0:00:03.20,0:00:08.40,Default,,0,0,0,,{\fad(200,200)\pos(320,50)}耳元,で囁くよ\Nおやすみ\hなさい
            [Fonts]
            Dialogue: 0,0:00:03.20,0:00:08.40,Default,,0,0,0,,Ignored
        """.trimIndent(), "ass")
        assertEquals(listOf(SubtitleCue(3200, 8400, "耳元,で囁くよ\nおやすみ なさい")), cues)
    }

    @Test fun assSupportsCustomColumnOrderAndOverlappingCues() {
        val cues = SubtitleParser.parse("""
            [Events]
            Format: Text, End, Start
            Dialogue: One, with comma,0:00:05.00,0:00:01.00
            Dialogue: Two,0:00:03.00,0:00:02.00
        """.trimIndent(), "ssa")
        assertEquals(2, cues.size)
        assertEquals("One, with comma", cues.first().text)
        assertEquals(listOf("One, with comma", "Two"), SubtitleParser.activeCues(cues, 2000).map { it.text })
        assertEquals(listOf("One, with comma"), SubtitleParser.activeCues(cues, 3000).map { it.text })
    }

    @Test fun malformedAndUnsupportedInputReturnsNoFabricatedCues() {
        assertTrue(SubtitleParser.parse("[00:99.00]Invalid seconds", "lrc").isEmpty())
        assertTrue(SubtitleParser.parse("00:65:00,000 --> 00:66:00,000\nInvalid minutes", "srt").isEmpty())
        assertTrue(SubtitleParser.parse("[Events]\nFormat: Style, Text\nDialogue: Default,No timing", "ass").isEmpty())
        assertTrue(SubtitleParser.parse("[00:01.00]Unknown format", "text").isEmpty())
        assertTrue(SubtitleParser.parse("", "vtt").isEmpty())
    }
}
