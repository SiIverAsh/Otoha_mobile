package io.github.otoha.mobile

import io.github.otoha.mobile.PlaybackRecovery.Action
import io.github.otoha.mobile.PlaybackRecovery.Condition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {
    private val recovery = PlaybackRecovery()

    private fun update(
        time: Long, condition: Condition = Condition.BUFFERING, online: Boolean? = true,
        item: String? = "track-one", wantsPlayback: Boolean = true,
    ): Action = recovery.update(time, item, wantsPlayback, condition, online)

    @Test fun `short buffers rely on the player and separate stalls start a new timer`() {
        assertEquals(Action.Wait(15_000), update(0))
        assertEquals(Action.Wait(1), update(14_999))
        assertEquals(Action.None, update(15_000, Condition.PLAYING))
        assertEquals(Action.Wait(15_000), update(19_000))
        assertEquals(Action.Retry(1), update(34_000))
        assertEquals(Action.None, update(60_000))
        recovery.retryCompleted(60_100)
        assertEquals(Action.Wait(15_000), update(60_100))
        assertEquals(Action.Retry(2), update(75_100))
    }

    @Test fun `offline waits indefinitely and a stalled track retries once on reconnection`() {
        assertEquals(Action.Wait(15_000), update(0))
        assertEquals(Action.None, update(100, online = false))
        assertEquals(Action.None, update(45_000, Condition.NETWORK_ERROR, online = false))
        assertEquals(Action.None, update(50_000, Condition.NETWORK_ERROR, online = null))
        assertEquals(Action.Retry(1), update(50_001, Condition.NETWORK_ERROR))
        assertEquals(Action.None, update(50_002, Condition.NETWORK_ERROR))
        assertEquals(Action.None, update(90_001))
    }

    @Test fun `losing network during a refresh gives the interrupted attempt back`() {
        assertEquals(Action.Wait(1_000), update(0, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(1), update(1_000, Condition.NETWORK_ERROR))
        assertEquals(Action.None, update(1_001, online = false))
        recovery.retryCompleted(1_002)
        assertEquals(Action.Retry(1), update(1_003))
        recovery.retryCompleted(1_004)
        assertEquals(Action.Wait(15_000), update(1_004))
    }

    @Test fun `stopping while refreshing or offline clears the pending retry for the same track`() {
        assertEquals(Action.Wait(15_000), update(0))
        assertEquals(Action.Retry(1), update(15_000))
        assertEquals(Action.None, update(15_100, Condition.IDLE))
        recovery.retryCompleted(15_110)
        assertEquals(Action.Wait(15_000), update(15_120))
        assertEquals(Action.Retry(1), update(30_120))
        assertEquals(Action.None, update(30_125, online = false))
        assertEquals(Action.None, update(30_130, Condition.IDLE, online = false))
        assertEquals(Action.Wait(15_000), update(30_131))
    }

    @Test fun `network errors use increasing delays and stop after three attempts`() {
        assertEquals(Action.Wait(1_000), update(0, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(1), update(1_000, Condition.NETWORK_ERROR))
        recovery.retryCompleted(1_100)
        assertEquals(Action.Wait(3_000), update(1_100, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(2), update(4_100, Condition.NETWORK_ERROR))
        recovery.retryCompleted(4_200)
        assertEquals(Action.Wait(8_000), update(4_200, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(3), update(12_200, Condition.NETWORK_ERROR))
        recovery.retryCompleted(12_300)
        assertEquals(Action.Exhausted, update(12_300, Condition.NETWORK_ERROR))
        assertEquals(Action.Exhausted, update(15_000, Condition.NETWORK_ERROR, online = null))
        assertEquals(Action.Exhausted, update(19_999, Condition.NETWORK_ERROR))
        assertEquals(Action.None, update(20_000, Condition.NETWORK_ERROR, online = false))
        assertEquals(Action.None, update(20_500, Condition.NETWORK_ERROR, online = null))
        assertEquals(Action.Retry(1), update(21_000, Condition.NETWORK_ERROR))
        assertEquals(Action.None, update(22_000, Condition.NETWORK_ERROR))
        recovery.retryCompleted(22_000)
        assertEquals(Action.Wait(3_000), update(22_000, Condition.NETWORK_ERROR))

        recovery.reset()
        assertEquals(Action.Wait(1_000), update(22_001, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(1), update(23_001, Condition.NETWORK_ERROR))
    }

    @Test fun `brief readiness retains the budget and stable playback replenishes it`() {
        assertEquals(Action.Wait(15_000), update(0))
        assertEquals(Action.Retry(1), update(15_000))
        assertEquals(Action.Wait(30_000), update(15_500, Condition.PLAYING))
        assertEquals(Action.Wait(3_000), update(16_000, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(2), update(19_000, Condition.NETWORK_ERROR))
        assertEquals(Action.Wait(30_000), update(19_500, Condition.PLAYING))
        assertEquals(Action.None, update(49_500, Condition.PLAYING))
        assertEquals(Action.Wait(1_000), update(50_000, Condition.NETWORK_ERROR))
        assertEquals(Action.Retry(1), update(51_000, Condition.NETWORK_ERROR))
    }

    @Test fun `last refreshed address gets a full buffering window before offering manual recovery`() {
        assertEquals(Action.Wait(15_000), update(0))
        for (attempt in 1..3) {
            val time = attempt * 15_000L
            assertEquals(Action.Retry(attempt), update(time))
            recovery.retryCompleted(time)
            assertEquals(Action.Wait(15_000), update(time))
        }
        assertEquals(Action.Exhausted, update(60_000))
        assertEquals(Action.Exhausted, update(90_000))
    }

    @Test fun `returning online never restarts a ready track`() {
        assertEquals(Action.None, update(0, Condition.PLAYING, online = false))
        assertEquals(Action.None, update(500, Condition.PLAYING))
        assertEquals(Action.Wait(15_000), update(1_000))
        assertEquals(Action.None, update(1_500, online = false))
        assertEquals(Action.None, update(1_600, Condition.PLAYING))
    }

    @Test fun `pausing clearing the queue or switching items drops the old attempt`() {
        assertEquals(Action.Wait(15_000), update(0))
        assertEquals(Action.Retry(1), update(15_000))
        assertEquals(Action.None, update(15_001, wantsPlayback = false))
        recovery.retryCompleted(15_010)
        assertEquals(Action.Wait(15_000), update(15_020))
        assertEquals(Action.Wait(15_000), update(25_000, item = "track-two"))
        assertEquals(Action.None, update(30_000, item = null))
        assertEquals(Action.None, update(30_001, Condition.IDLE))
        assertEquals(Action.Wait(15_000), update(39_000))
        assertEquals(Action.Retry(1), update(54_000))
        assertEquals(Action.None, update(54_001, Condition.OTHER))
    }

    @Test fun `fresh addresses can help expired and transient HTTP responses`() {
        listOf(401, 403, 404, 408, 410, 429, 500, 503, 504).forEach {
            assertTrue("HTTP $it", isRetryablePlaybackHttpStatus(it))
        }
        listOf(200, 301, 400, 416, 451).forEach {
            assertFalse("HTTP $it", isRetryablePlaybackHttpStatus(it))
        }
    }
}
