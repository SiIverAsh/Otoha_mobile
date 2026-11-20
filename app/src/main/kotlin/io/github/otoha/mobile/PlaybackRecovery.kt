@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> generateSequence(error.cause) { it.cause }
            .none { it is CertificateException || it is SSLPeerUnverifiedException }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val response = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            response != null && isRetryablePlaybackHttpStatus(response.responseCode)
        }
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> generateSequence(error.cause) { it.cause }.any {
            it is UnknownHostException || it is SocketTimeoutException || it is SocketException || it is EOFException
        }
        else -> false
    }
}

// A refreshed media URL may resolve an expired or missing resource.
internal fun isRetryablePlaybackHttpStatus(code: Int): Boolean = when (code) {
    401, 403, 404, 408, 410, 429, in 500..599 -> true
    else -> false
}

/** Called on the playback thread. Times use a monotonic clock. */
internal class PlaybackRecovery {
    enum class Condition { PLAYING, BUFFERING, NETWORK_ERROR, IDLE, OTHER }

    sealed interface Action {
        data object None : Action
        data class Wait(val delayMs: Long) : Action
        data class Retry(val attempt: Int) : Action
        data object Exhausted : Action
    }

    private var currentItem: String? = null
    private var lastCondition: Condition? = null
    private var deadlineMs: Long? = null
    private var attempts = 0
    private var inFlight = false
    private var waitingForNetwork = false

    fun reset() {
        currentItem = null
        lastCondition = null
        deadlineMs = null
        attempts = 0
        inFlight = false
        waitingForNetwork = false
    }

    fun update(nowMs: Long, itemKey: String?, wantsPlayback: Boolean, condition: Condition, online: Boolean?): Action {
        if (!wantsPlayback || itemKey == null || condition == Condition.IDLE || condition == Condition.OTHER) {
            reset()
            return Action.None
        }
        if (currentItem != itemKey) {
            reset()
            currentItem = itemKey
        }
        if (lastCondition != condition) {
            deadlineMs = null
            lastCondition = condition
        }
        if (online == false) {
            if (inFlight) attempts--
            inFlight = false
            waitingForNetwork = true
        }
        if (condition == Condition.PLAYING) {
            if (online == true) waitingForNetwork = false
            inFlight = false
            if (attempts > 0) {
                waitUntil(nowMs, STABLE_PLAYBACK_MS)?.let { return it }
                attempts = 0
            }
            return Action.None
        }
        if (online == false) {
            deadlineMs = null
            return Action.None
        }
        if (inFlight || (waitingForNetwork && online == null)) return Action.None
        if (waitingForNetwork) {
            if (attempts >= ERROR_DELAYS_MS.size) attempts = 0
            deadlineMs = nowMs
        } else {
            waitUntil(nowMs, recoveryDelayMs(condition))?.let { return it }
        }
        waitingForNetwork = false
        if (attempts >= ERROR_DELAYS_MS.size) return Action.Exhausted
        inFlight = true
        attempts++
        return Action.Retry(attempts)
    }

    /** Signal completion for the current attempt only. Canceled requests must not call this method. */
    fun retryCompleted(nowMs: Long) {
        if (!inFlight) return
        inFlight = false
        deadlineMs = lastCondition?.let { nowMs + recoveryDelayMs(it) }
    }

    private fun waitUntil(nowMs: Long, delayMs: Long): Action.Wait? {
        val deadline = deadlineMs ?: (nowMs + delayMs).also { deadlineMs = it }
        return if (nowMs < deadline) Action.Wait(deadline - nowMs) else null
    }

    private fun recoveryDelayMs(condition: Condition): Long =
        if (condition == Condition.BUFFERING) STALLED_BUFFER_MS else ERROR_DELAYS_MS.getOrNull(attempts) ?: 0L

    private companion object {
        const val STALLED_BUFFER_MS = 15_000L
        const val STABLE_PLAYBACK_MS = 30_000L
        val ERROR_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L)
    }
}
