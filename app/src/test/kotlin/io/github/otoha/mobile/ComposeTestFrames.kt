package io.github.otoha.mobile

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** Drives Compose frames independently of Android's shared vsync scheduler. */
internal class ComposeTestFrames(activity: ComponentActivity, content: @Composable () -> Unit) : AutoCloseable {
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + clock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val composeView = ComposeView(activity)
    private var frameTimeNanos = 0L

    init {
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        composeView.setParentCompositionContext(recomposer)
        composeView.setContent(content)
        activity.setContentView(composeView)
    }

    /** Advances the composition and allows Android to lay out each frame. */
    fun advance(count: Int) {
        repeat(count) {
            Snapshot.sendApplyNotifications()
            mainLooper.idle()
            frameTimeNanos += FrameInterval.toNanos()
            clock.sendFrame(frameTimeNanos)
            mainLooper.idleFor(FrameInterval)
            (composeView.getChildAt(0) as? RootForTest)?.measureAndLayoutForTest()
        }
    }

    override fun close() {
        recomposer.cancel()
        scope.cancel()
        mainLooper.idle()
    }

    private companion object {
        val FrameInterval: Duration = Duration.ofMillis(16)
    }
}
