@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.otoha.mobile

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.otoha.core.AudioTrack
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
import kotlinx.serialization.encodeToString

data class PlaybackUi(
    val connected: Boolean = false,
    val work: WorkSummary? = null,
    val tracks: List<AudioTrack> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
    val subtitleText: String = "",
    val subtitleError: String? = null,
    val subtitleLoading: Boolean = false,
) { val track: AudioTrack? get() = tracks.getOrNull(index) }

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
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { queueDirty = true; refresh() }
        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = mutableState.value.copy(error = "播放失败：${error.errorCodeName}。可重新打开作品刷新链接。")
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
        mutableState.value = mutableState.value.copy(connected = true, work = work, tracks = tracks,
            index = p.currentMediaItemIndex.coerceAtLeast(0), playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
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
        val items = tracks.map { track ->
            val extras = Bundle().apply {
                putString(MediaExtras.WORK, appJson.encodeToString(work))
                putString(MediaExtras.TRACK, appJson.encodeToString(track))
                putString(MediaExtras.SOURCE, work.ref.source.name)
                putString(MediaExtras.RECORDING_TOKEN, recordingToken)
            }
            MediaItem.Builder().setMediaId("${work.ref.key}:${track.id}").setUri(track.url)
                .apply { if (track.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setAlbumTitle(work.title)
                    .setArtist(work.circle).setArtworkUri(work.coverUrl.takeIf(String::isNotBlank)?.let(Uri::parse))
                    .setExtras(extras).build()).build()
        }
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
    fun recordingToken(): String? = player?.currentMediaItem?.mediaMetadata?.extras?.getString(MediaExtras.RECORDING_TOKEN)
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
