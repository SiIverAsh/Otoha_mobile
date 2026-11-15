package io.github.otoha.mobile

import io.github.otoha.core.SubtitleCue
import io.github.otoha.core.SubtitleParser

/** Presentation has no parser-initialization work when no subtitle is loaded. */
internal object SubtitleDisplay {
    fun textFor(cues: List<SubtitleCue>, positionMs: Long): String = if (cues.isEmpty()) "" else
        SubtitleParser.activeCues(cues, positionMs).joinToString("\n") { it.text }

    fun content(text: String, loading: Boolean, error: String?): String =
        error ?: if (loading) "正在加载字幕…" else text.ifBlank { "♪" }
}
