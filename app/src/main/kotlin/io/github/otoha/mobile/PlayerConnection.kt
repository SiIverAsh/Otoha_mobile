@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.PlaybackSnapshot
import io.github.otoha.core.SourceHttp
import io.github.otoha.core.SubtitleCue
import io.github.otoha.core.SubtitleParser
import io.github.otoha.core.Subtitle
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUi(
    val connected: Boolean = false,
    val work: WorkSummary? = null,
    val tracks: List<AudioTrack> = emptyList(),
    val index: Int = 0,
    val showPause: Boolean = false,
    val buffering: Boolean = false,
    val recoveryStatus: RecoveryStatus = RecoveryStatus.NONE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
    val subtitleText: String = "",
    val subtitleError: String? = null,
    val subtitleLoading: Boolean = false,
) { val track: AudioTrack? get() = tracks.getOrNull(index) }

internal class ManualPlaybackRetry private constructor(
    val snapshot: PlaybackSnapshot,
    val token: String,
    private val mediaId: String,
    private val playWhenReady: Boolean,
    private val revision: Long,
) {
    fun currentSnapshot(player: Player, currentRevision: Long): PlaybackSnapshot? {
        val item = player.currentMediaItem ?: return null
        if (revision != currentRevision || player.playWhenReady != playWhenReady ||
            player.currentMediaItemIndex != snapshot.index || item.mediaId != mediaId ||
            item.mediaMetadata.extras?.getString(MediaExtras.RECORDING_TOKEN) != token) return null
        return snapshot.copy(positionMs = player.currentPosition.coerceAtLeast(0), speed = player.playbackParameters.speed)
    }

    companion object {
        fun capture(player: Player, revision: Long): ManualPlaybackRetry? {
            val item = player.currentMediaItem ?: return null
            val token = item.mediaMetadata.extras?.getString(MediaExtras.RECORDING_TOKEN) ?: return null
            val snapshot = runCatching { player.playbackSnapshot() }.getOrNull() ?: return null
            return ManualPlaybackRetry(snapshot, token, item.mediaId, player.playWhenReady, revision)
        }
    }
}

class PlayerConnection(context: Context, private val store: AppStore) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(PlaybackUi())
    val state = mutableState.asStateFlow()
    private var player: MediaController? = null
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var subtitleIdentity: Triple<WorkRef?, String?, Subtitle?>? = null
    private var queueDirty = true
    private var cachedWork: WorkSummary? = null
    private var cachedTracks: List<AudioTrack> = emptyList()
    private var future: ListenableFuture<MediaController>? = null
    private var closed = false
    private var connectionGeneration = 0
    private var retryRevision = 0L
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) retryRevision++
            queueDirty = true
            refresh()
        }
    }

    init {
        connect()
        scope.launch { while (isActive) { delay(500); refresh() } }
        scope.launch {
            val http = SourceHttp()
            state.map { Triple(it.work?.ref, it.track?.id, it.track?.subtitle) }.distinctUntilChanged()
                .collectLatest { identity ->
                    val (workRef, _, subtitle) = identity
                    subtitleIdentity = identity
                    subtitleCues = emptyList()
                    mutableState.value = mutableState.value.copy(subtitleText = "", subtitleError = null,
                        subtitleLoading = workRef != null && subtitle != null)
                    if (workRef != null && subtitle != null) {
                        try {
                            subtitleCues = SubtitleParser.parse(http.getSubtitle(subtitle.url, workRef.source), subtitle.format)
                            mutableState.value = mutableState.value.copy(subtitleLoading = false,
                                subtitleError = if (subtitleCues.isEmpty()) "字幕中没有可显示的时间轴" else null)
                            refresh()
                        } catch (cancel: CancellationException) { throw cancel
                        } catch (_: Exception) {
                            mutableState.value = mutableState.value.copy(subtitleLoading = false, subtitleError = "字幕暂不可用")
                        }
                    }
                }
        }
    }

    private fun connect() {
        if (closed) return
        retryRevision++
        val generation = ++connectionGeneration
        val pending = MediaController.Builder(appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (!closed && generation == connectionGeneration && player === controller) {
                        player = null
                        mutableState.value = mutableState.value.copy(connected = false,
                            error = "播放器连接已断开，请重试")
                    }
                }
            }).buildAsync()
        future = pending
        pending.addListener(callback@{
            if (closed || generation != connectionGeneration) return@callback
            runCatching {
                player = pending.get().also { it.addListener(listener) }
                queueDirty = true
                refresh()
            }.onFailure {
                if (!closed && generation == connectionGeneration)
                    mutableState.value = mutableState.value.copy(connected = false,
                        error = "无法连接播放器：${it.message ?: "请重试"}")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun retryConnection() {
        if (closed) return
        connectionGeneration++
        player?.removeListener(listener)
        player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        mutableState.value = mutableState.value.copy(connected = false, error = null)
        connect()
    }

    private fun refresh() {
        if (closed) return
        val p = player ?: return
        if (queueDirty) {
            cachedWork = p.currentMediaItem?.mediaMetadata?.extras?.getString(MediaExtras.WORK)
                ?.let { runCatching { appJson.decodeFromString<WorkSummary>(it) }.getOrNull() }
            cachedTracks = (0 until p.mediaItemCount).mapNotNull { index ->
                p.getMediaItemAt(index).mediaMetadata.extras?.getString(MediaExtras.TRACK)
                    ?.let { runCatching { appJson.decodeFromString<AudioTrack>(it) }.getOrNull() }
            }
            queueDirty = false
        }
        val work = cachedWork
        val tracks = cachedTracks
        val track = tracks.getOrNull(p.currentMediaItemIndex)
        val matchingSubtitle = subtitleIdentity == Triple(work?.ref, track?.id, track?.subtitle)
        val recoveryName = p.sessionExtras.getString(MediaExtras.RECOVERY)
        val recoveryStatus = RecoveryStatus.entries.firstOrNull { it.name == recoveryName } ?: RecoveryStatus.NONE
        mutableState.value = mutableState.value.copy(connected = true, work = work, tracks = tracks,
            index = p.currentMediaItemIndex.coerceAtLeast(0),
            showPause = p.playWhenReady && p.playbackState != Player.STATE_ENDED,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            recoveryStatus = recoveryStatus,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: tracks.getOrNull(p.currentMediaItemIndex)?.durationMs ?: 0,
            speed = p.playbackParameters.speed,
            subtitleText = if (matchingSubtitle) SubtitleDisplay.textFor(subtitleCues, p.currentPosition) else "",
            subtitleError = if (matchingSubtitle) mutableState.value.subtitleError else null,
            subtitleLoading = track?.subtitle != null && (!matchingSubtitle || mutableState.value.subtitleLoading),
            error = p.playerError?.let { "播放失败：${it.errorCodeName}。可刷新地址后重试。" })
    }

    fun play(work: WorkSummary, tracks: List<AudioTrack>, index: Int, positionMs: Long = 0, speed: Float = 1f,
        continuationToken: String? = null, isContinuation: Boolean = false) {
        val p = player ?: return
        if (tracks.isEmpty() || index !in tracks.indices) return
        val recordingToken = if (isContinuation) store.continuePlayback(continuationToken) ?: return else store.beginPlayback()
        val items = playbackMediaItems(work, tracks, recordingToken)
        p.setMediaItems(items, index, positionMs.coerceAtLeast(0))
        p.setPlaybackSpeed(speed.coerceIn(.5f, 2f))
        p.prepare()
        p.play()
    }

    fun play(detail: WorkDetail, track: AudioTrack) {
        val queue = detail.queueFor(track)
        play(detail.summary, queue, queue.indexOfFirst { it.id == track.id })
    }
    fun toggle() {
        player?.run {
            when {
                playbackState == Player.STATE_ENDED -> { seekToDefaultPosition(); play() }
                playWhenReady -> pause()
                else -> { if (playbackState == Player.STATE_IDLE) prepare(); play() }
            }
        }
    }
    fun previous() { player?.seekToPreviousMediaItem() }
    fun next() { player?.seekToNextMediaItem() }
    fun seek(positionMs: Long) { player?.seekTo(positionMs.coerceAtLeast(0)) }
    fun select(index: Int) { player?.seekToDefaultPosition(index) }
    fun speed(value: Float) { player?.setPlaybackSpeed(value) }
    fun pause() { player?.pause() }
    fun clear() { player?.run { stop(); clearMediaItems() } }
    internal fun captureRetry(): ManualPlaybackRetry? = player?.let { ManualPlaybackRetry.capture(it, retryRevision) }
    internal fun currentRetrySnapshot(retry: ManualPlaybackRetry): PlaybackSnapshot? =
        player?.let { retry.currentSnapshot(it, retryRevision) }
    fun close() {
        if (closed) return
        closed = true
        connectionGeneration++
        scope.cancel()
        player?.removeListener(listener)
        player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }
}
