package io.github.otoha.mobile

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OtohaButtonsTest {
    @Test
    fun longLabelStaysInsideNarrowButtonAtLargeFontSize() {
        val label = "A long action label that needs more space than this button has"
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var composeRoot: RootForTest? = null
        try {
            controller.get().setContent {
                val view = LocalView.current
                SideEffect { composeRoot = view as? RootForTest }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    MaterialTheme { OtohaOutlinedButton(onClick = {}, label = label, modifier = Modifier.width(120.dp)) }
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

            val nodes = checkNotNull(composeRoot).semanticsOwner.getAllSemanticsNodes(
                mergingEnabled = false,
                skipDeactivatedNodes = true,
            )
            val button = nodes.single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
            val text = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label }
            val layouts = mutableListOf<TextLayoutResult>()
            val getTextLayout = checkNotNull(text.config[SemanticsActions.GetTextLayoutResult].action)
            assertTrue(getTextLayout(layouts))
            val layout = layouts.single()

            assertEquals(2, layout.lineCount)
            assertTrue(layout.isLineEllipsized(1))
            assertTrue(text.boundsInRoot.left > button.boundsInRoot.left)
            assertTrue(text.boundsInRoot.right < button.boundsInRoot.right)
            assertTrue(text.boundsInRoot.top >= button.boundsInRoot.top)
            assertTrue(text.boundsInRoot.bottom <= button.boundsInRoot.bottom)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun pressingScalesButtonAndReleasingRestoresItsSize() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        var composeRoot: RootForTest? = null
        var buttonPress: ButtonPress? = null
        try {
            ComposeTestFrames(controller.get()) {
                val view = LocalView.current
                val press = rememberButtonPress()
                SideEffect {
                    composeRoot = view as? RootForTest
                    buttonPress = press
                }
                MaterialTheme {
                    TextButton(
                        onClick = {},
                        modifier = Modifier.width(120.dp).then(press.modifier),
                        interactionSource = press.interactionSource,
                    ) {
                        ButtonLabel("Play")
                    }
                }
            }.use { frames ->
                frames.advance(24)

                val nodes = checkNotNull(composeRoot).semanticsOwner.getAllSemanticsNodes(
                    mergingEnabled = false,
                    skipDeactivatedNodes = true,
                )
                val button = nodes.single { it.config.getOrNull(SemanticsProperties.Role) == Role.Button }
                val layoutSize = button.size
                val animation = checkNotNull(buttonPress)
                val restScale = animation.scale.value
                val press = PressInteraction.Press(Offset.Zero)

                assertEquals(1f, restScale, 0.001f)
                assertTrue(animation.interactionSource.tryEmit(press))
                frames.advance(16)
                assertTrue(animation.scale.value < restScale)
                assertEquals(layoutSize, button.size)

                assertTrue(animation.interactionSource.tryEmit(PressInteraction.Release(press)))
                frames.advance(60)
                assertEquals(restScale, animation.scale.value, 0.001f)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
