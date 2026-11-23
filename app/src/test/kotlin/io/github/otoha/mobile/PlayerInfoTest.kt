package io.github.otoha.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.Chapter
import io.github.otoha.core.ContentRating
import io.github.otoha.core.FacetKind
import io.github.otoha.core.FacetValue
import io.github.otoha.core.SourceId
import io.github.otoha.core.Subtitle
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h720dp")
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerInfoTest {
    @Test
    fun formatLabelDetectsHlsAndExtensions() {
        val hls = AudioTrack("1", "Track 1", "https://cdn.example/a.m3u8", isHls = true)
        val mp3 = AudioTrack("2", "01_intro.mp3", "https://cdn.example/audio")
        val flac = AudioTrack("3", "02_rain", "https://cdn.example/files/rain.flac?token=abc")
        val unknown = AudioTrack("4", "03_stream", "https://cdn.example/stream")

        assertEquals("HLS 流式", PlayerInfo.formatLabel(hls))
        assertEquals("MP3", PlayerInfo.formatLabel(mp3))
        assertEquals("FLAC", PlayerInfo.formatLabel(flac))
        assertEquals("在线音频", PlayerInfo.formatLabel(unknown))
        assertEquals("在线音频", PlayerInfo.formatLabel(null))
    }

    @Test
    fun playbackStatusReflectsStateAndIncognito() {
        assertEquals("正在播放", PlayerInfo.playbackStatus(PlaybackUi(showPause = true), incognito = false))
        assertEquals("正在播放 · 无痕", PlayerInfo.playbackStatus(PlaybackUi(showPause = true), incognito = true))
        assertEquals("正在缓冲", PlayerInfo.playbackStatus(PlaybackUi(buffering = true, showPause = true), incognito = false))
        assertEquals("已暂停", PlayerInfo.playbackStatus(PlaybackUi(showPause = false), incognito = false))
        assertEquals("播放异常", PlayerInfo.playbackStatus(PlaybackUi(error = "failed"), incognito = false))
    }

    @Test
    fun activeChapterIndexTracksPosition() {
        val chapters = listOf(
            Chapter("开场", 0L),
            Chapter("雨声", 60_000L),
            Chapter("晚安", 180_000L),
        )
        assertEquals(-1, PlayerInfo.activeChapterIndex(emptyList(), 10_000L))
        assertEquals(0, PlayerInfo.activeChapterIndex(chapters, 0L))
        assertEquals(0, PlayerInfo.activeChapterIndex(chapters, 59_999L))
        assertEquals(1, PlayerInfo.activeChapterIndex(chapters, 60_000L))
        assertEquals(2, PlayerInfo.activeChapterIndex(chapters, 240_000L))
    }

    @Test
    fun infoRowsIncludeRichWorkAndTrackMetadata() {
        val ref = WorkRef(SourceId.ASMR_ONE, "123456")
        val track1 = AudioTrack(
            id = "t1",
            title = "01_ear_cleaning.wav",
            url = "https://example.org/01.wav",
            directory = listOf("WAV", "无SE"),
            durationMs = 300_000L,
            chapters = listOf(Chapter("开场", 0L), Chapter("放松", 120_000L)),
            subtitle = Subtitle("https://example.org/01.vtt", "vtt"),
        )
        val track2 = AudioTrack(
            id = "t2",
            title = "02_sleep.wav",
            url = "https://example.org/02.wav",
            directory = listOf("WAV", "无SE"),
            durationMs = 600_000L,
        )
        val work = WorkSummary(
            ref = ref,
            title = "雨夜安眠",
            workNumber = "RJ123456",
            circle = "静谧工坊",
            actors = listOf("星野", "白石"),
            tags = listOf("助眠", "雨声"),
            rating = ContentRating.GENERAL,
        )
        val state = PlaybackUi(
            connected = true,
            work = work,
            tracks = listOf(track1, track2),
            index = 0,
            durationMs = 300_000L,
        )

        val rows = PlayerInfo.infoRows(work, state).associate { it.label to it.value }
        assertEquals("ASMR.one · RJ123456", rows["音源与编号"])
        assertEquals("静谧工坊", rows["社团"])
        assertEquals("星野 / 白石", rows["声优"])
        assertEquals("WAV / 无SE", rows["目录 / 版本"])
        assertEquals("WAV", rows["音频格式"])
        assertEquals("5:00", rows["当前时长"])
        assertEquals("第 1 / 2 首 · 总时长 15:00", rows["播放队列"])
        assertEquals("VTT 字幕", rows["字幕"])
        assertEquals("2 个章节", rows["章节"])
        assertEquals("全年龄", rows["内容分级"])
    }

    @Test
    fun audioInfoCardRendersMetadataAndTags() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val ref = WorkRef(SourceId.ASMR_ONE, "123456")
        val tagFacet = FacetValue(SourceId.ASMR_ONE, FacetKind.TAG, "1", "助眠")
        val work = WorkSummary(
            ref = ref,
            title = "雨夜安眠",
            workNumber = "RJ123456",
            circle = "静谧工坊",
            actors = listOf("星野"),
            tags = listOf("助眠"),
            rating = ContentRating.GENERAL,
            facets = listOf(tagFacet),
        )
        val track = AudioTrack(
            id = "t1",
            title = "01_rain.mp3",
            url = "https://example.org/01.mp3",
            directory = listOf("MP3"),
            durationMs = 185_000L,
        )
        val state = PlaybackUi(work = work, tracks = listOf(track), index = 0, durationMs = 185_000L)
        var composeRoot: RootForTest? = null

        try {
            ComposeTestFrames(controller.get()) {
                val view = LocalView.current
                SideEffect { composeRoot = view as? RootForTest }
                MaterialTheme {
                    AudioInfoCard(work = work, state = state, onFacet = {})
                }
            }.use { frames ->
                frames.advance(24)
                val texts = checkNotNull(composeRoot).semanticsOwner
                    .getAllSemanticsNodes(mergingEnabled = false, skipDeactivatedNodes = true)
                    .flatMap { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
                    }

                assertTrue(texts.contains("音频信息"))
                assertTrue(texts.contains("ASMR.one · RJ123456"))
                assertTrue(texts.contains("静谧工坊"))
                assertTrue(texts.contains("星野"))
                assertTrue(texts.contains("MP3"))
                assertTrue(texts.contains("3:05"))
                assertTrue(texts.contains("助眠"))
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
