package io.github.otoha.mobile

import io.github.otoha.core.AudioTrack
import io.github.otoha.core.Chapter
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.WorkSummary

internal data class AudioInfoRow(val label: String, val value: String)

internal object PlayerInfo {
    private val audioExtensions = setOf(
        "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "webm", "wma", "alac", "aiff",
    )

    fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
    }

    fun playbackStatus(state: PlaybackUi, incognito: Boolean): String {
        val status = when {
            state.recoveryStatus == RecoveryStatus.EXHAUSTED ||
                (state.recoveryStatus == RecoveryStatus.NONE && state.error != null) -> "播放异常"
            state.buffering -> "正在缓冲"
            state.showPause -> "正在播放"
            else -> "已暂停"
        }
        return if (incognito) "$status · 无痕" else status
    }

    fun formatLabel(track: AudioTrack?): String {
        if (track == null) return "在线音频"
        if (track.isHls) return "HLS 流式"
        val fromTitle = track.title.substringAfterLast('.', "").lowercase()
        if (fromTitle in audioExtensions) return fromTitle.uppercase()
        val fromUrl = track.url.substringBefore('?').substringAfterLast('/', "")
            .substringAfterLast('.', "").lowercase()
        if (fromUrl in audioExtensions) return fromUrl.uppercase()
        return "在线音频"
    }

    fun directoryLabel(track: AudioTrack?): String? {
        if (track == null) return null
        if (track.directory.isNotEmpty()) return track.directory.joinToString(" / ")
        return null
    }

    fun subtitleLabel(track: AudioTrack?): String =
        track?.subtitle?.let { "${it.format.uppercase()} 字幕" } ?: "无字幕"

    fun ratingLabel(rating: ContentRating): String? = when (rating) {
        ContentRating.GENERAL -> "全年龄"
        ContentRating.RESTRICTED -> "受限"
        ContentRating.UNKNOWN -> null
    }

    fun activeChapterIndex(chapters: List<Chapter>, positionMs: Long): Int {
        if (chapters.isEmpty()) return -1
        var active = -1
        for (index in chapters.indices) {
            if (chapters[index].startMs <= positionMs) active = index else break
        }
        return active
    }

    fun badges(work: WorkSummary, state: PlaybackUi): List<String> = buildList {
        add(work.ref.source.displayName())
        if (work.workNumber.isNotBlank()) add(work.workNumber)
        add(formatLabel(state.track))
        state.track?.subtitle?.let { add("${it.format.uppercase()} 字幕") }
        ratingLabel(work.rating)?.let(::add)
    }

    fun infoRows(work: WorkSummary, state: PlaybackUi): List<AudioInfoRow> = buildList {
        val sourceValue = listOf(work.ref.source.displayName(), work.workNumber)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        add(AudioInfoRow("音源与编号", sourceValue))
        if (work.circle.isNotBlank()) add(AudioInfoRow("社团", work.circle))
        if (work.actors.isNotEmpty()) add(AudioInfoRow("声优", work.actors.joinToString(" / ")))
        directoryLabel(state.track)?.let { add(AudioInfoRow("目录 / 版本", it)) }
        add(AudioInfoRow("音频格式", formatLabel(state.track)))
        add(
            AudioInfoRow(
                "当前时长",
                if (state.durationMs > 0) formatDuration(state.durationMs) else "获取中…",
            ),
        )
        if (state.tracks.isNotEmpty()) {
            val totalKnown = state.tracks.size > 1 && state.tracks.all { it.durationMs > 0 }
            val totalMs = if (totalKnown) state.tracks.sumOf { it.durationMs } else 0L
            val queueValue = buildString {
                append("第 ${state.index + 1} / ${state.tracks.size} 首")
                if (totalMs > 0) append(" · 总时长 ${formatDuration(totalMs)}")
            }
            add(AudioInfoRow("播放队列", queueValue))
        }
        add(AudioInfoRow("字幕", subtitleLabel(state.track)))
        val chapters = state.track?.chapters.orEmpty()
        if (chapters.isNotEmpty()) add(AudioInfoRow("章节", "${chapters.size} 个章节"))
        ratingLabel(work.rating)?.let { add(AudioInfoRow("内容分级", it)) }
    }

    fun tagFacets(work: WorkSummary): List<FacetValue> =
        work.facets.filter { it.kind == FacetKind.TAG }.distinctBy { it.key }

    fun tagLabels(work: WorkSummary): List<String> =
        work.tags.map(String::trim).filter(String::isNotEmpty).distinct()
}
