@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import io.github.otoha.core.AudioTrack
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
