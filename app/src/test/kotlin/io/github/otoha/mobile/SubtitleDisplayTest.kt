package io.github.otoha.mobile

import io.github.otoha.core.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDisplayTest {
    @Test fun `subtitle text updates on seek back forward and exclusive cue ending`() {
        val cues = listOf(SubtitleCue(1_000, 3_000, "第一行"), SubtitleCue(2_000, 3_000, "翻译"),
            SubtitleCue(4_000, 6_000, "下一段"))
        assertEquals("第一行\n翻译", SubtitleDisplay.textFor(cues, 2_500))
        assertEquals("下一段", SubtitleDisplay.textFor(cues, 4_500))
        assertEquals("第一行", SubtitleDisplay.textFor(cues, 1_500))
        assertEquals("", SubtitleDisplay.textFor(cues, 3_000))
        assertEquals("", SubtitleDisplay.textFor(emptyList(), 4_500))
    }

    @Test fun `loading and failure remain visible in subtitle region instead of looking like missing lyrics`() {
        assertEquals("正在加载字幕…", SubtitleDisplay.content("", true, null))
        assertEquals("字幕暂不可用", SubtitleDisplay.content("", false, "字幕暂不可用"))
        assertEquals("♪", SubtitleDisplay.content("", false, null))
        assertEquals("当前字幕", SubtitleDisplay.content("当前字幕", false, null))
    }
}
