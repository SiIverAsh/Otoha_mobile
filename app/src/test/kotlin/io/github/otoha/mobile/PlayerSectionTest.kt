package io.github.otoha.mobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerSectionTest {
    @Test
    fun reopeningDuringExitKeepsSectionContentMounted() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var expanded by mutableStateOf(true)
        var mounts = 0
        var disposals = 0
        var height = 0

        try {
            ComposeTestFrames(controller.get()) {
                MaterialTheme {
                    Column {
                        Box(Modifier.onSizeChanged { height = it.height }) {
                            PlayerSection("播放队列", "1 / 1", expanded, { expanded = !expanded }) {
                                DisposableEffect(Unit) {
                                    mounts++
                                    onDispose { disposals++ }
                                }
                                Spacer(Modifier.height(48.dp))
                            }
                        }
                    }
                }
            }.use { frames ->
                frames.advance(24)
                assertEquals(1, mounts)
                val openHeight = height
                assertTrue("The section should be laid out", openHeight > 0)

                expanded = false
                frames.advance(10)
                val closingHeight = height
                assertTrue("Closing height $closingHeight should be below $openHeight", closingHeight < openHeight)
                assertEquals(0, disposals)

                expanded = true
                frames.advance(60)
                assertEquals(openHeight, height)
                assertEquals(1, mounts)
                assertEquals(0, disposals)

                expanded = false
                frames.advance(60)
                assertTrue("The section should finish collapsing", height < closingHeight)
                assertEquals(1, disposals)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
