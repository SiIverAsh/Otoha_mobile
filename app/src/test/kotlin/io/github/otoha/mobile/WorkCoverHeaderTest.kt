package io.github.otoha.mobile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import io.github.otoha.core.SourceId
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
@Config(sdk = [36], qualifiers = "w320dp-h600dp")
@LooperMode(LooperMode.Mode.PAUSED)
class WorkCoverHeaderTest {
    @Test
    fun coverSitsBesideTitleAndMissingArtworkKeepsTheSameSpace() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val title = "A work title"
        val work = WorkSummary(WorkRef(SourceId.ASMR_ONE, "1"), title)
        var coverUrl by mutableStateOf("android.resource://${activity.packageName}/${R.drawable.ic_otoha}")
        var composeRoot: RootForTest? = null

        try {
            ComposeTestFrames(activity) {
                val view = LocalView.current
                SideEffect { composeRoot = view as? RootForTest }
                MaterialTheme {
                    WorkCoverHeader(work.copy(coverUrl = coverUrl)) { Text(title) }
                }
            }.use { frames ->
                frames.advance(24)
                val root = checkNotNull(composeRoot)
                val nodes = root.semanticsOwner.getAllSemanticsNodes(
                    mergingEnabled = false,
                    skipDeactivatedNodes = true,
                )
                val cover = nodes.coverWithDescription("作品封面").boundsInRoot
                val titleBounds = nodes.single { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == title
                }.boundsInRoot

                assertTrue(cover.height > 0f)
                assertEquals(cover.width * 3f / 4f, cover.height, 1f)
                assertTrue(cover.right < titleBounds.left)
                assertTrue(titleBounds.top < cover.bottom)

                coverUrl = ""
                frames.advance(24)
                val fallback = root.semanticsOwner.getAllSemanticsNodes(
                    mergingEnabled = false,
                    skipDeactivatedNodes = true,
                ).coverWithDescription("暂无作品封面")
                assertEquals(cover, fallback.boundsInRoot)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun List<SemanticsNode>.coverWithDescription(description: String): SemanticsNode =
        single { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(description)
        }
}
