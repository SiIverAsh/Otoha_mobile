package io.github.otoha.core

import kotlin.math.abs

private const val MIN_TRACK_COUNT = 2
private const val MIN_DURATION_TOLERANCE_MS = 1_100L
private const val MAX_DURATION_TOLERANCE_MS = 5_000L
private const val DURATION_TOLERANCE_RATIO = 0.005
private const val NUMBERED_STEM =
    "^(?:(?:Track|Trk|Tr|Chapter|Ch|トラック|第)[ _.-]*)?([0-9]{1,4})(?:[ _.-]*[話章])?"
private const val VERSION_SUFFIX =
    "(?:[ _.-]*(?:(?:SE|BGM|環境音)[ _]*(?:あり|なし|有り|無し)|\\(No[ _]+SE\\))(?:[ _.-]*ver\\.?)?)?$"

private val genericName = Regex("$NUMBERED_STEM$VERSION_SUFFIX", RegexOption.IGNORE_CASE)
private val namedTrack = Regex("${NUMBERED_STEM}[ ._、・:–—-]+.*\\p{L}", RegexOption.IGNORE_CASE)

private data class NumberedTrack(
    val index: Int,
    val ordinal: Int,
    val durationMs: Long,
    val isGeneric: Boolean,
)

/** Adds official display names only to complete ASMR.one groups verified by timing. */
internal suspend fun fillAsmrOneTrackTitles(
    detail: WorkDetail,
    requestTrackPage: suspend (String) -> String,
): WorkDetail {
    if (!detail.summary.workNumber.startsWith("RJ")) return detail
    // Check formats independently, while keeping the app's existing queues intact.
    val groups = detail.tracks.withIndex()
        .groupBy { (_, track) -> track.directory to track.title.substringAfterLast('.', "").lowercase() }
        .values.mapNotNull(::completeNumberedGroup)
    if (groups.isEmpty()) return detail

    val entries = loadDlsiteTrackList(detail.summary.workNumber, requestTrackPage)
    val titlesByIndex = mutableMapOf<Int, String>()
    for (group in groups) {
        if (group.size != entries.size) continue
        val pairs = group.zip(entries)
        if (pairs.any { (track, entry) -> !durationMatches(track.durationMs, entry.durationMs) }) continue
        for ((track, entry) in pairs) {
            if (track.isGeneric) titlesByIndex[track.index] = entry.title
        }
    }
    if (titlesByIndex.isEmpty()) return detail
    return detail.copy(tracks = detail.tracks.mapIndexed { index, track ->
        titlesByIndex[index]?.let { track.copy(title = it) } ?: track
    })
}

private fun completeNumberedGroup(group: List<IndexedValue<AudioTrack>>): List<NumberedTrack>? {
    if (group.size < MIN_TRACK_COUNT) return null
    val tracks = group.map { (index, track) ->
        if (track.durationMs <= 0) return null
        val stem = track.title.substringBeforeLast('.').foldAsciiWidth()
        val generic = genericName.matchEntire(stem)
        val match = generic ?: namedTrack.find(stem) ?: return null
        NumberedTrack(index, match.groupValues[1].toInt(), track.durationMs, generic != null)
    }.sortedBy(NumberedTrack::ordinal)
    val first = tracks.first().ordinal
    if (first !in 0..1 || tracks.none(NumberedTrack::isGeneric)) return null
    if (tracks.withIndex().any { (index, track) -> track.ordinal != first + index }) return null
    return tracks
}

private fun String.foldAsciiWidth(): String = buildString(length) {
    for (character in this@foldAsciiWidth) {
        append(when (character) {
            in '！'..'～' -> '!' + (character - '！')
            '　' -> ' '
            else -> character
        })
    }
}

private fun durationMatches(actualMs: Long, publishedMs: Long): Boolean {
    val tolerance = (publishedMs * DURATION_TOLERANCE_RATIO)
        .coerceIn(MIN_DURATION_TOLERANCE_MS.toDouble(), MAX_DURATION_TOLERANCE_MS.toDouble())
    return abs(actualMs - publishedMs) <= tolerance
}
