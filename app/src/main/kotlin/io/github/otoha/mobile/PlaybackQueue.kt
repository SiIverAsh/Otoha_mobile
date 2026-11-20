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
        MediaItem.Builder()
            .setMediaId("${work.ref.key}:${track.id}")
            .setUri(track.url)
            .apply { if (track.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(metadata)
            .build()
    }
}
