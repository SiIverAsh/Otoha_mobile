package io.github.otoha.core

import kotlin.math.abs
import kotlin.math.roundToLong

private const val MIN_TRACK_COUNT = 2
private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_DURATION_TOLERANCE_MS = 10_000L
private const val ROUNDING_ALLOWANCE_MS = 500L
// Media3 represents clip positions in microseconds.
private const val MAX_CLIP_POSITION_MS = Long.MAX_VALUE / 1_000L
private const val PLAYLIST_HEADER = "#EXTM3U"
private const val PLAYLIST_END = "#EXT-X-ENDLIST"
private const val SEGMENT_DURATION = "#EXTINF:"

private val decimalSeconds = Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
private val unsupportedTimelineTags = listOf(
    "#EXT-X-STREAM-INF:",
    "#EXT-X-I-FRAME-STREAM-INF:",
    "#EXT-X-MEDIA:",
    "#EXT-X-SESSION-DATA:",
    "#EXT-X-SESSION-KEY:",
    "#EXT-X-CONTENT-STEERING:",
    "#EXT-X-I-FRAMES-ONLY",
    "#EXT-X-SKIP:",
    "#EXT-X-PART:",
    "#EXT-X-PRELOAD-HINT:",
)

/** Uses the published chapter order when the HLS duration agrees with the track list. */
internal fun splitAsmr18FromPublishedTracks(
    track: AudioTrack,
    entries: List<DlsiteTrack>,
    playlist: String,
): List<AudioTrack> {
    if (!track.isHls || track.clipStartMs != null || entries.size < MIN_TRACK_COUNT) return listOf(track)
    val measuredDurationMs = finitePlaylistDurationMs(playlist) ?: return listOf(track)

    var publishedDurationMs = 0L
    val starts = entries.map { entry ->
        if (entry.title.isBlank() || entry.durationMs !in 1..(MAX_CLIP_POSITION_MS - publishedDurationMs)) {
            return listOf(track)
        }
        val startMs = publishedDurationMs
        publishedDurationMs += entry.durationMs
        startMs
    }
    // Each published duration may be rounded to whole seconds.
    val toleranceMs = (entries.size * MILLIS_PER_SECOND + ROUNDING_ALLOWANCE_MS)
        .coerceAtMost(MAX_DURATION_TOLERANCE_MS)
    if (abs(publishedDurationMs - measuredDurationMs) > toleranceMs || starts.last() >= measuredDurationMs) {
        return listOf(track)
    }

    return entries.mapIndexed { index, entry ->
        val startMs = starts[index]
        val endMs = starts.getOrNull(index + 1) ?: measuredDurationMs
        track.copy(
            id = "${track.id}:chapter:$startMs",
            title = entry.title,
            durationMs = endMs - startMs,
            chapters = emptyList(),
            clipStartMs = startMs,
            sourceTrackId = track.id,
            sourceTrackTitle = track.title,
        )
    }
}

private fun finitePlaylistDurationMs(playlist: String): Long? {
    val lines = playlist.lineSequence().iterator()
    if (!lines.hasNext() || lines.next() != PLAYLIST_HEADER) return null

    var hasEnd = false
    var durationSeconds = 0.0
    var pendingSegmentSeconds: Double? = null
    for (line in lines) {
        when {
            line == PLAYLIST_HEADER -> return null
            line == PLAYLIST_END -> {
                if (hasEnd) return null
                // ENDLIST may appear before the last EXTINF entry.
                hasEnd = true
            }
            line.startsWith(SEGMENT_DURATION) -> {
                if (pendingSegmentSeconds != null || ',' !in line) return null
                val timing = line.removePrefix(SEGMENT_DURATION).substringBefore(',')
                if (!decimalSeconds.matches(timing)) return null
                val seconds = timing.toDoubleOrNull() ?: return null
                if (!seconds.isFinite() || seconds <= 0) return null
                pendingSegmentSeconds = seconds
            }
            unsupportedTimelineTags.any { line.startsWith(it) } -> return null
            line.isBlank() || line.startsWith('#') -> continue
            else -> {
                durationSeconds += pendingSegmentSeconds ?: return null
                pendingSegmentSeconds = null
            }
        }
    }
    if (!hasEnd || pendingSegmentSeconds != null) return null
    return (durationSeconds * MILLIS_PER_SECOND).roundToLong().takeIf { it in 1..MAX_CLIP_POSITION_MS }
}
