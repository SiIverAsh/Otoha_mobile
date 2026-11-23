@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackRecoveryControllerTest {
    private val work = WorkSummary(WorkRef(SourceId.ASMR_ONE, "123"), "Album")
    private val tracks = listOf(
        AudioTrack("1", "First", "https://media.test/old/1"),
        AudioTrack("2", "Second", "https://media.test/old/2"),
    )
    private val fresh = tracks.map { it.copy(url = "https://media.test/new/${it.id}") }
    private val response = CompletableDeferred<WorkDetail>()
    private val requests = mutableListOf<WorkRef>()
    private val main = shadowOf(Looper.getMainLooper())
    private lateinit var player: BufferingPlayer
    private lateinit var controller: PlaybackRecoveryController

    // Keep ExoPlayer's real playlist, seeks and play intent. Simulate buffering without any network I/O.
    private class BufferingPlayer(realPlayer: ExoPlayer) : ForwardingPlayer(realPlayer) {
        var prepares = 0
        var simulatedState = Player.STATE_BUFFERING
        var failure: PlaybackException? = null
        override fun getPlaybackState(): Int = simulatedState
        override fun getPlayerError(): PlaybackException? = failure
        override fun prepare() { prepares++ }
    }

    @Before fun setUp() {
        player = BufferingPlayer(ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build())
        player.setMediaItems(playbackMediaItems(work, tracks, "original-queue"), 1, 4_000)
        player.setPlaybackSpeed(1.5f)
        player.play()
        controller = PlaybackRecoveryController(player, { ref ->
            requests += ref
            response.await()
        }, { true }, {})
    }

    @After fun tearDown() {
        controller.close()
        player.release()
    }

    private fun disconnectAndReconnect() {
        controller.onNetworkChanged(false)
        controller.evaluate()
        main.idleFor(Duration.ofMinutes(1))
        assertTrue(requests.isEmpty())
        controller.onNetworkChanged(true)
        main.idle()
        assertEquals(listOf(work.ref), requests)
    }

    private fun finishRequest() {
        response.complete(WorkDetail(work, fresh.reversed()))
        main.idle()
    }

    private fun queueUrls(): List<String> = (0 until player.mediaItemCount).map { index ->
        player.getMediaItemAt(index).localConfiguration!!.uri.toString()
    }

    @Test fun reconnectRefreshesTheQueueAndContinuesAtTheLatestPosition() {
        disconnectAndReconnect()
        player.seekTo(8_700)
        finishRequest()

        assertEquals(fresh.map { it.url }, queueUrls())
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(8_700L, player.currentPosition)
        assertEquals(1.5f, player.playbackParameters.speed, 0f)
        assertTrue(player.playWhenReady)
        assertEquals(1, player.prepares)
        for (index in tracks.indices) {
            val extras = player.getMediaItemAt(index).mediaMetadata.extras
            assertEquals("original-queue", extras?.getString(MediaExtras.RECORDING_TOKEN))
        }
    }

    @Test fun reconnectMigratesAnOlderWholeStreamQueueAtTheLatestSeekPosition() {
        val legacyWork = work.copy(ref = WorkRef(SourceId.ASMR18, "allages/rj012345"))
        val source = tracks.first().copy(isHls = true)
        val refreshedSource = source.copy(url = "https://media.test/new/1", sourceTrackId = source.id)
        val chapters = listOf(
            refreshedSource.copy(id = "1:0", clipStartMs = 0, durationMs = 5_000),
            refreshedSource.copy(id = "1:5000", title = "Last", clipStartMs = 5_000),
        )
        player.setMediaItems(playbackMediaItems(legacyWork, listOf(source), "original-queue"), 0, 4_000)
        controller.onNetworkChanged(false)
        controller.onNetworkChanged(true)
        main.idle()
        assertEquals(listOf(legacyWork.ref), requests)

        player.seekTo(8_700)
        response.complete(WorkDetail(legacyWork, chapters))
        main.idle()

        assertEquals(chapters.map { it.url }, queueUrls())
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals(3_700L, player.currentPosition)
        assertEquals(1.5f, player.playbackParameters.speed, 0f)
        assertTrue(player.playWhenReady)
        assertEquals(1, player.prepares)
        val snapshot = requireNotNull(player.playbackSnapshot())
        assertEquals(chapters, snapshot.tracks)
        assertEquals(3_700L, snapshot.positionMs)
    }

    @Test fun aBriefOnlineBufferDoesNotRefreshButAStalledBufferDoes() {
        controller.onNetworkChanged(true)
        controller.evaluate()
        main.idleFor(Duration.ofSeconds(14))
        assertTrue(requests.isEmpty())
        main.idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(work.ref), requests)
        finishRequest()
        assertEquals(fresh.map { it.url }, queueUrls())
        assertEquals(1, player.prepares)
    }

    @Test fun reconnectAlsoResumesAfterThePlayerHasEnteredIdleWithANetworkError() {
        player.simulatedState = Player.STATE_IDLE
        player.failure = PlaybackException("timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        disconnectAndReconnect()
        finishRequest()
        assertEquals(fresh.map { it.url }, queueUrls())
        assertEquals(4_000L, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertEquals(1, player.prepares)
    }

    @Test fun aTrackThatRecoversOnItsOwnIsNotInterruptedByALateResponse() {
        disconnectAndReconnect()
        player.simulatedState = Player.STATE_READY
        controller.evaluate()
        finishRequest()
        assertEquals(tracks.map { it.url }, queueUrls())
        assertEquals(0, player.prepares)
    }

    @Test fun pausingWhileTheRefreshIsInFlightKeepsThePlayerPaused() {
        disconnectAndReconnect()
        player.pause()
        controller.evaluate()
        finishRequest()

        assertFalse(player.playWhenReady)
        assertEquals(tracks.map { it.url }, queueUrls())
        assertEquals(0, player.prepares)
    }

    @Test fun switchingTracksWhileTheRefreshIsInFlightKeepsTheNewSelection() {
        disconnectAndReconnect()
        player.seekTo(0, 2_000)
        controller.evaluate()
        finishRequest()

        assertEquals(0, player.currentMediaItemIndex)
        assertEquals(2_000L, player.currentPosition)
        assertEquals(tracks.map { it.url }, queueUrls())
        assertEquals(0, player.prepares)
    }
}
