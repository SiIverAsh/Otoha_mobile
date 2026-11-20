@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.otoha.core.ContentRating
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.requestHeaders
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private const val WORK_EXTRA = "otoha.work"
private const val TRACK_EXTRA = "otoha.track"
private const val SOURCE_EXTRA = "otoha.source"
private const val RECORDING_TOKEN_EXTRA = "otoha.recording-token"

/** Every source owns a data-source factory, including HLS playlists, segments and keys. */
private class SourceMediaFactory(service: PlaybackService) : MediaSource.Factory {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()
    private val delegates = SourceId.entries.associateWith { source ->
        DefaultMediaSourceFactory(DefaultDataSource.Factory(service,
            OkHttpDataSource.Factory(http).setDefaultRequestProperties(source.requestHeaders())))
    }
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = runCatching { SourceId.valueOf(mediaItem.mediaMetadata.extras?.getString(SOURCE_EXTRA).orEmpty()) }
            .getOrDefault(SourceId.ASMR_ONE)
        return delegates.getValue(source).createMediaSource(mediaItem)
    }
    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        delegates.values.forEach { it.setDrmSessionManagerProvider(provider) }; return this
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegates.values.forEach { it.setLoadErrorHandlingPolicy(policy) }; return this
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var recovery: PlaybackRecoveryController
    private lateinit var network: PlaybackNetworkMonitor
    private val handler = Handler(Looper.getMainLooper())
    private val store get() = (application as OtohaApplication).store
    private val checkpoint = object : Runnable {
        override fun run() {
            val deadline = store.sleepDeadline.value
            if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) {
                session?.player?.pause()
                store.sleepAfter(0)
            }
            if (session?.player?.isPlaying == true) saveCheckpoint()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(SourceMediaFactory(this)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { saveCheckpoint() }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { saveCheckpoint() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo, reason: Int) { saveCheckpoint() }
                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { saveCheckpoint() }
            })
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return if (controller.packageName == packageName || controller.isTrusted)
                        super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
                }
                override fun onPlayerInteractionFinished(session: MediaSession, controllerInfo: MediaSession.ControllerInfo,
                    playerCommands: Player.Commands) {
                    // ExoPlayer can retain playWhenReady and the network error after an explicit stop.
                    if (playerCommands.contains(Player.COMMAND_STOP) && player.playbackState == Player.STATE_IDLE) player.pause()
                }
            }).build()
        recovery = PlaybackRecoveryController(player,
            refreshDetail = ::refreshDetail,
            isVisible = { work -> store.data.value.showRestricted || work.rating == ContentRating.GENERAL },
            onStatus = { status -> session?.setSessionExtras(Bundle().apply { putString(MediaExtras.RECOVERY, status.name) }) })
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = recovery.evaluate()
        })
        network = PlaybackNetworkMonitor(this, recovery::onNetworkChanged)
        network.start()
        handler.postDelayed(checkpoint, 5_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private suspend fun refreshDetail(ref: WorkRef): WorkDetail {
        val mirror = store.data.value.asmrOneApiBaseUrl
        val detail = (application as OtohaApplication).source(ref.source).detail(ref)
        check(ref.source != SourceId.ASMR_ONE || store.data.value.asmrOneApiBaseUrl == mirror) { "音源镜像已切换" }
        return detail
    }

    private fun saveCheckpoint() {
        val player = session?.player ?: return
        runCatching {
            store.savePlayback(player.playbackSnapshot() ?: return,
                player.currentMediaItem?.mediaMetadata?.extras?.getString(RECORDING_TOKEN_EXTRA))
        }
    }

    override fun onDestroy() {
        network.close()
        recovery.close()
        handler.removeCallbacks(checkpoint)
        store.sleepAfter(0)
        saveCheckpoint()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

internal object MediaExtras {
    const val RECOVERY = "otoha.recovery"
    const val WORK = WORK_EXTRA
    const val TRACK = TRACK_EXTRA
    const val SOURCE = SOURCE_EXTRA
    const val RECORDING_TOKEN = RECORDING_TOKEN_EXTRA
}
