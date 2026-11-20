package io.github.otoha.mobile

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

enum class RecoveryStatus(val message: String?) {
    NONE(null),
    OFFLINE("等待网络恢复，随后将自动续播"),
    REFRESHING("正在刷新播放地址…"),
    RETRYING("连接中断，准备自动重试…"),
    EXHAUSTED("自动恢复失败，请手动重试"),
}

/** The service owns this controller so recovery continues after the player screen closes. */
internal class PlaybackRecoveryController(
    private val player: Player,
    private val refreshDetail: suspend (WorkRef) -> WorkDetail,
    private val isVisible: (WorkSummary) -> Boolean,
    private val onStatus: (RecoveryStatus) -> Unit,
) {
    private val policy = PlaybackRecovery()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable(::evaluate)
    private var online: Boolean? = null
    private var request: Job? = null
    private var requestKey: String? = null
    private var status = RecoveryStatus.NONE
    private var applying = false
    private var closed = false

    fun onNetworkChanged(online: Boolean) {
        this.online = online
        evaluate()
    }

    fun evaluate() {
        if (closed || applying) return
        handler.removeCallbacks(tick)
        val key = itemKey()
        val condition = condition()
        val stalled = player.playWhenReady && key != null && condition.needsRecovery()
        if (!stalled || online == false || requestKey != key) cancelRequest()
        val action = policy.update(SystemClock.elapsedRealtime(), key, player.playWhenReady, condition, online)
        publish(when {
            !stalled -> RecoveryStatus.NONE
            online == false -> RecoveryStatus.OFFLINE
            action == PlaybackRecovery.Action.Exhausted -> RecoveryStatus.EXHAUSTED
            request != null || action is PlaybackRecovery.Action.Retry -> RecoveryStatus.REFRESHING
            condition == PlaybackRecovery.Condition.NETWORK_ERROR -> RecoveryStatus.RETRYING
            else -> RecoveryStatus.NONE
        })
        when (action) {
            is PlaybackRecovery.Action.Wait -> handler.postDelayed(tick, action.delayMs)
            is PlaybackRecovery.Action.Retry -> refresh(checkNotNull(key))
            else -> Unit
        }
    }

    private fun itemKey(): String? {
        val item = player.currentMediaItem ?: return null
        val token = item.mediaMetadata.extras?.getString(MediaExtras.RECORDING_TOKEN) ?: return null
        return "$token:${player.currentMediaItemIndex}:${item.mediaId}"
    }

    private fun condition(): PlaybackRecovery.Condition {
        val error = player.playerError
        if (error != null) return if (isRetryablePlaybackError(error)) PlaybackRecovery.Condition.NETWORK_ERROR
            else PlaybackRecovery.Condition.OTHER
        return when (player.playbackState) {
            Player.STATE_READY -> PlaybackRecovery.Condition.PLAYING
            Player.STATE_BUFFERING -> PlaybackRecovery.Condition.BUFFERING
            Player.STATE_IDLE -> PlaybackRecovery.Condition.IDLE
            else -> PlaybackRecovery.Condition.OTHER
        }
    }

    private fun PlaybackRecovery.Condition.needsRecovery() =
        this == PlaybackRecovery.Condition.BUFFERING || this == PlaybackRecovery.Condition.NETWORK_ERROR

    private fun refresh(key: String) {
        requestKey = key
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = player.playbackSnapshot() ?: return@launch
                val detail = refreshDetail(snapshot.work.ref)
                ensureActive()
                if (itemKey() != key || !player.playWhenReady || !condition().needsRecovery() || online == false) return@launch
                if (!isVisible(detail.summary)) return@launch
                val tracks = refreshedQueue(snapshot.tracks, detail)
                val token = player.currentMediaItem?.mediaMetadata?.extras?.getString(MediaExtras.RECORDING_TOKEN)
                val items = playbackMediaItems(detail.summary, tracks, token)
                // Read the live position after the request, including any seek made while it was pending.
                // Replacing media items preserves playWhenReady, speed and the queue's recording permission.
                applying = true
                try {
                    player.setMediaItems(items, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
                    player.prepare()
                } finally {
                    applying = false
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Keep the original queue and position available for the next attempt.
            } finally {
                if (request === coroutineContext.job) {
                    request = null
                    requestKey = null
                    policy.retryCompleted(SystemClock.elapsedRealtime())
                    evaluate()
                }
            }
        }
        request = job
        job.start()
    }

    private fun cancelRequest() {
        val previous = request
        request = null
        requestKey = null
        previous?.cancel()
    }

    private fun publish(next: RecoveryStatus) {
        if (next != status) {
            status = next
            onStatus(next)
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacks(tick)
        cancelRequest()
        scope.cancel()
    }
}
