package io.github.otoha.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import io.github.otoha.core.AudioTrack
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkDetail
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h1200dp")
@LooperMode(LooperMode.Mode.PAUSED)
class WorkDetailTrackListTest {
    private val work = WorkSummary(WorkRef(SourceId.ASMR18, "allages/rj123456"), "Work")
    private val withBgm = AudioTrack("file1", "BGM あり", "https://media.test/with", directory = listOf("file1"))
    private val withoutBgm = AudioTrack("file2", "BGM なし", "https://media.test/without", directory = listOf("file2"))

    @Test
    fun splitVersionsDisplayTheirSourceTitlesAsSeparateHeadings() {
        val tracks = chapters(withBgm) + chapters(withoutBgm)

        inspectTrackList(tracks) { nodes ->
            assertEquals(setOf(withBgm.title, withoutBgm.title), nodes.versionHeadings())
            assertTrue("选择版本与曲目" in nodes.texts())
            assertEquals(2, nodes.texts().count { it == "Opening" })
        }
    }

    @Test
    fun aWholeStreamKeepsItsVersionHeadingWhenAnotherVersionSplits() {
        inspectTrackList(chapters(withBgm) + withoutBgm) { nodes ->
            assertEquals(setOf(withBgm.title, withoutBgm.title), nodes.versionHeadings())
            assertTrue("整轨播放" in nodes.texts())
        }
    }

    @Test
    fun aSingleChapterQueueKeepsTheCompactChapterHeading() {
        inspectTrackList(chapters(withBgm)) { nodes ->
            assertTrue("章节曲目" in nodes.texts())
            assertFalse(withBgm.title in nodes.texts())
            assertTrue(nodes.versionHeadings().isEmpty())
        }
    }

    private fun chapters(source: AudioTrack): List<AudioTrack> = listOf(
        source.copy(
            id = "${source.id}:opening",
            title = "Opening",
            durationMs = 10_000,
            clipStartMs = 0,
            sourceTrackId = source.id,
            sourceTrackTitle = source.title,
        ),
        source.copy(
            id = "${source.id}:ending",
            title = "Ending",
            clipStartMs = 10_000,
            sourceTrackId = source.id,
            sourceTrackTitle = source.title,
        ),
    )

    private fun inspectTrackList(tracks: List<AudioTrack>, inspect: (List<SemanticsNode>) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var composeRoot: RootForTest? = null

        try {
            ComposeTestFrames(controller.get()) {
                val view = LocalView.current
                SideEffect { composeRoot = view as? RootForTest }
                MaterialTheme {
                    DetailScreen(
                        detail = WorkDetail(work, tracks),
                        favorite = false,
                        connected = true,
                        onFavorite = {},
                        onFacet = {},
                        onPlay = {},
                    )
                }
            }.use { frames ->
                frames.advance(24)
                val nodes = checkNotNull(composeRoot).semanticsOwner.getAllSemanticsNodes(
                    mergingEnabled = false,
                    skipDeactivatedNodes = true,
                )
                inspect(nodes)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun List<SemanticsNode>.texts(): List<String> = mapNotNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
    }

    private fun List<SemanticsNode>.versionHeadings(): Set<String> = filter { node ->
        node.config.contains(SemanticsProperties.Heading)
    }.texts().toSet()
}
