@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.PlaybackSnapshot
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackQueueTest {
    private val work = WorkSummary(WorkRef(SourceId.ASMR_ONE, "123"), "Album")
    private val first = AudioTrack("a", "First", "https://media.test/old/a", listOf("A"))
    private val second = AudioTrack("b", "Second", "https://media.test/old/b", listOf("A"))
    private val chapteredWork = work.copy(ref = WorkRef(SourceId.ASMR18, "allages/rj012345"))
    private val source = first.copy(isHls = true)
    private val firstChapter = source.copy(id = "a:0", durationMs = 4_000, clipStartMs = 0, sourceTrackId = source.id)
    private val finalChapter = source.copy(id = "a:4000", title = "Last", clipStartMs = 4_000, sourceTrackId = source.id)

    @Test fun refreshKeepsTheSelectedVersionAndQueueOrder() {
        val newFirst = first.copy(url = "https://media.test/new/a")
        val newSecond = second.copy(url = "https://media.test/new/b", isHls = true)
        val detail = WorkDetail(work, listOf(newSecond, first.copy(directory = listOf("B")), newFirst))

        assertEquals(listOf(newFirst, newSecond), refreshedQueue(listOf(first, second), detail))
    }

    @Test fun refreshRejectsMissingAndAmbiguousTracks() {
        val wrongVersion = first.copy(directory = listOf("B"))
        val duplicate = first.copy(url = "https://media.test/duplicate")
        assertThrows(IllegalStateException::class.java) {
            refreshedQueue(listOf(first), WorkDetail(work, listOf(wrongVersion)))
        }
        assertThrows(IllegalStateException::class.java) {
            refreshedQueue(listOf(first), WorkDetail(work, listOf(first, duplicate)))
        }
    }

    @Test fun mediaItemsCarryUpdatedSourceMetadataAndOnlyTheGivenRecordingToken() {
        val hls = second.copy(url = "https://media.test/new/playlist", isHls = true)
        val items = playbackMediaItems(work, listOf(first, hls), "queue-token")
        assertNull(items.first().localConfiguration?.mimeType)
        val item = items.last()
        assertEquals("${work.ref.key}:${hls.id}", item.mediaId)
        assertEquals(hls.url, item.localConfiguration?.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, item.localConfiguration?.mimeType)
        val extras = requireNotNull(item.mediaMetadata.extras)
        assertEquals(work.ref.source.name, extras.getString(MediaExtras.SOURCE))
        assertEquals(work, appJson.decodeFromString<WorkSummary>(requireNotNull(extras.getString(MediaExtras.WORK))))
        assertEquals(hls, appJson.decodeFromString<AudioTrack>(requireNotNull(extras.getString(MediaExtras.TRACK))))
        assertEquals("queue-token", extras.getString(MediaExtras.RECORDING_TOKEN))

        val withoutToken = playbackMediaItems(work, listOf(first), null).single()
        assertFalse(requireNotNull(withoutToken.mediaMetadata.extras).containsKey(MediaExtras.RECORDING_TOKEN))
    }

    @Test fun hlsChaptersClipTheSharedSourceAtAbsoluteBoundaries() {
        val middle = finalChapter.copy(durationMs = 2_000)
        val tail = finalChapter.copy(id = "a:6000", clipStartMs = 6_000)

        val items = playbackMediaItems(chapteredWork, listOf(middle, tail), null)

        assertEquals(source.url, items[0].localConfiguration?.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, items[0].localConfiguration?.mimeType)
        assertEquals(4_000L, items[0].clippingConfiguration.startPositionMs)
        assertEquals(6_000L, items[0].clippingConfiguration.endPositionMs)
        assertEquals(6_000L, items[1].clippingConfiguration.startPositionMs)
        assertEquals(C.TIME_END_OF_SOURCE, items[1].clippingConfiguration.endPositionMs)
    }

    @Test fun refreshingKeepsSourcePositionWhenAChapterIsInserted() {
        val saved = PlaybackSnapshot(chapteredWork, listOf(firstChapter, finalChapter), 1, 3_200, 1.5f)
        val inserted = finalChapter.copy(id = "a:6000", title = "Inserted", clipStartMs = 6_000)
        val updated = listOf(firstChapter, finalChapter.copy(durationMs = 2_000), inserted)
        val refreshed = refreshedPlayback(saved, WorkDetail(chapteredWork, updated))

        assertEquals(saved.copy(tracks = updated, index = 2, positionMs = 1_200), refreshed)
    }

    @Test fun refreshingContinuesInTheSameStreamIfChapterCuesDisappear() {
        val saved = PlaybackSnapshot(chapteredWork, listOf(firstChapter, finalChapter), 1, 2_300)
        val updated = source.copy(url = "https://media.test/new/a")
        val refreshed = refreshedPlayback(saved, WorkDetail(chapteredWork, listOf(updated)))

        assertEquals(saved.copy(tracks = listOf(updated), index = 0, positionMs = 6_300), refreshed)
    }

    @Test fun aLegacyCheckpointUsesTheNextChapterAtAnExactBoundary() {
        val saved = PlaybackSnapshot(chapteredWork, listOf(source), 0, 4_000)
        val updated = listOf(firstChapter, finalChapter)
        val refreshed = refreshedPlayback(saved, WorkDetail(chapteredWork, updated))

        assertEquals(saved.copy(tracks = updated, index = 1, positionMs = 0), refreshed)
    }

    @Test fun aCompletedCheckpointRestartsAPlayableChapter() {
        val updated = listOf(firstChapter, finalChapter.copy(durationMs = 6_000))
        val detail = WorkDetail(chapteredWork, updated)
        val legacy = PlaybackSnapshot(chapteredWork, listOf(source), 0, 10_000)
        val chapter = PlaybackSnapshot(chapteredWork, updated, 1, 6_000)

        assertEquals(legacy.copy(tracks = updated, positionMs = 0), refreshedPlayback(legacy, detail))
        assertEquals(chapter.copy(positionMs = 0), refreshedPlayback(chapter, detail))
    }

    @Test fun manualRetryHonorsRecentSeeksAndPlaybackIntentEvenWhenRequestedWhilePaused() {
        val player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        try {
            val tracks = listOf(first, second)
            player.setMediaItems(playbackMediaItems(work, tracks, "queue"), 0, 500)
            val paused = requireNotNull(ManualPlaybackRetry.capture(player, 1))
            player.seekTo(6_000)
            player.setPlaybackSpeed(1.5f)
            val latest = requireNotNull(paused.currentSnapshot(player, 1))
            assertEquals(6_000L, latest.positionMs)
            assertEquals(1.5f, latest.speed, 0f)

            player.play()
            val playing = requireNotNull(ManualPlaybackRetry.capture(player, 2))
            player.pause()
            assertNull(playing.currentSnapshot(player, 2))
            player.play()
            assertNull(playing.currentSnapshot(player, 3))
            player.seekTo(1, 0)
            assertNull(playing.currentSnapshot(player, 2))
            player.setMediaItems(playbackMediaItems(work, tracks.reversed(), "queue"), 0, 0)
            assertNull(playing.currentSnapshot(player, 2))
            player.setMediaItems(playbackMediaItems(work, tracks, "different-queue"), 0, 0)
            assertNull(playing.currentSnapshot(player, 2))
        } finally {
            player.release()
        }
    }
}
