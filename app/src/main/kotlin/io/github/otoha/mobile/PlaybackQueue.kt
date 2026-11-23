@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.PlaybackSnapshot
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkSummary
import kotlinx.serialization.encodeToString

internal fun Player.playbackSnapshot(): PlaybackSnapshot? {
    val extras = currentMediaItem?.mediaMetadata?.extras ?: return null
    val work = extras.getString(MediaExtras.WORK)?.let { appJson.decodeFromString<WorkSummary>(it) } ?: return null
    val tracks = (0 until mediaItemCount).map { index ->
        val raw = getMediaItemAt(index).mediaMetadata.extras?.getString(MediaExtras.TRACK) ?: return null
        appJson.decodeFromString<AudioTrack>(raw)
    }
    return PlaybackSnapshot(work, tracks, currentMediaItemIndex, currentPosition.coerceAtLeast(0), playbackParameters.speed)
}

internal fun refreshedQueue(original: List<AudioTrack>, detail: WorkDetail): List<AudioTrack> =
    original.map { old ->
        detail.tracks.singleOrNull { it.id == old.id && it.directory == old.directory }
            ?: error("原队列中的曲目已变化，请打开作品重新选择版本")
    }

/** Map a saved checkpoint to the latest playable tracks for its work. */
internal fun refreshedPlayback(snapshot: PlaybackSnapshot, detail: WorkDetail): PlaybackSnapshot {
    val selected = snapshot.tracks.getOrNull(snapshot.index) ?: error("播放记录不完整，请重新选择曲目")
    val canRemapSource = snapshot.work.ref.source == SourceId.ASMR18 &&
        (snapshot.tracks.size == 1 || selected.sourceTrackId != null)
    if (canRemapSource) {
        refreshedSourcePlayback(snapshot, selected, detail)?.let { return it }
    }
    return snapshot.copy(work = detail.summary, tracks = refreshedQueue(snapshot.tracks, detail))
}

private fun refreshedSourcePlayback(
    snapshot: PlaybackSnapshot,
    selected: AudioTrack,
    detail: WorkDetail,
): PlaybackSnapshot? {
    val sourceId = selected.sourceTrackId ?: selected.id
    val absolutePositionMs = (selected.clipStartMs ?: 0L) + snapshot.positionMs.coerceAtLeast(0)
    val chapters = detail.tracks.filter { it.sourceTrackId == sourceId && it.directory == selected.directory }
    if (chapters.isEmpty()) {
        if (selected.sourceTrackId == null) return null
        val source = detail.tracks.singleOrNull { it.id == sourceId && it.directory == selected.directory }
            ?: return null
        return snapshot.copy(work = detail.summary, tracks = listOf(source), index = 0, positionMs = absolutePositionMs)
    }

    // Use absolute source time so added or removed boundaries preserve the seek.
    val index = chapters.indexOfLast { chapter ->
        val startMs = checkNotNull(chapter.clipStartMs)
        absolutePositionMs >= startMs &&
            (chapter.durationMs == 0L || absolutePositionMs - startMs < chapter.durationMs)
    }
    if (index < 0) {
        // Replay the completed chapter or start an older full stream at
        // the first chapter.
        val replayIndex = chapters.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        return snapshot.copy(work = detail.summary, tracks = chapters, index = replayIndex, positionMs = 0)
    }
    val positionMs = absolutePositionMs - checkNotNull(chapters[index].clipStartMs)
    return snapshot.copy(work = detail.summary, tracks = chapters, index = index, positionMs = positionMs)
}

internal fun playbackMediaItems(
    work: WorkSummary,
    tracks: List<AudioTrack>,
    recordingToken: String?,
): List<MediaItem> {
    val serializedWork = appJson.encodeToString(work)
    val artwork = work.coverUrl.takeIf(String::isNotBlank)?.let(Uri::parse)
    return tracks.map { track ->
        val extras = Bundle().apply {
            putString(MediaExtras.WORK, serializedWork)
            putString(MediaExtras.TRACK, appJson.encodeToString(track))
            putString(MediaExtras.SOURCE, work.ref.source.name)
            if (recordingToken != null) putString(MediaExtras.RECORDING_TOKEN, recordingToken)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setAlbumTitle(work.title)
            .setArtist(work.circle)
            .setArtworkUri(artwork)
            .setExtras(extras)
            .build()
        val item = MediaItem.Builder()
            .setMediaId("${work.ref.key}:${track.id}")
            .setUri(track.url)
            .setMediaMetadata(metadata)
        if (track.isHls) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val startMs = track.clipStartMs
        if (startMs != null) {
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
            if (track.durationMs > 0) clipping.setEndPositionMs(startMs + track.durationMs)
            item.setClippingConfiguration(clipping.build())
        }
        item.build()
    }
}
