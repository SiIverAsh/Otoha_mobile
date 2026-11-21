package io.github.otoha.mobile

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h600dp")
class WorkGridTest {
    @Test
    fun worksShareRowsWhileHeadingAndStatusSpanBothColumns() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val gridState = LazyGridState()

        try {
            controller.get().setContent {
                WorkGrid(gridState) {
                    fullWidthItem { GridCell() }
                    items(3) { GridCell() }
                    fullWidthItem { GridCell() }
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

            val cells = gridState.layoutInfo.visibleItemsInfo
            assertEquals(listOf(0, 1, 1, 2, 3), cells.map { it.row })
            assertEquals(listOf(0, 0, 1, 0, 0), cells.map { it.column })
            assertEquals(cells[0].size.width, cells[4].size.width)
            assertTrue(cells[0].size.width > cells[1].size.width + cells[2].size.width)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

@Composable
private fun GridCell() {
    Spacer(Modifier.fillMaxWidth().height(48.dp))
}
