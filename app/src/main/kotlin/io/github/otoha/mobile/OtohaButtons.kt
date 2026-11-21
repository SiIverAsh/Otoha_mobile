package io.github.otoha.mobile

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val PRESSED_SCALE = 0.96f
private const val PRESS_DURATION_MS = 100
private val ButtonPressSpec = tween<Float>(durationMillis = PRESS_DURATION_MS)
private val ButtonReleaseSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)
private val ButtonContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** Connects a Material control's press interactions to its visual scale. */
internal class ButtonPress(val interactionSource: MutableInteractionSource, val scale: State<Float>) {
    val modifier: Modifier = Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Scales the rendered control without changing its measured size. */
@Composable
internal fun rememberButtonPress(): ButtonPress {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (isPressed) PRESSED_SCALE else 1f,
        animationSpec = if (isPressed) ButtonPressSpec else ButtonReleaseSpec,
        label = "button press",
    )
    return remember(interactionSource, scale) { ButtonPress(interactionSource, scale) }
}

/** Fits the label to its content area while retaining the full text for accessibility. */
@Composable
internal fun ButtonLabel(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text = text,
        modifier = modifier.clipToBounds(),
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Displays a filled action with a bounded label and press feedback. */
@Composable
internal fun OtohaButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val press = rememberButtonPress()
    Button(
        onClick = onClick,
        modifier = modifier.then(press.modifier),
        enabled = enabled,
        interactionSource = press.interactionSource,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonLabel(label)
    }
}

/** Displays an outlined action with a bounded label and press feedback. */
@Composable
internal fun OtohaOutlinedButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val press = rememberButtonPress()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.then(press.modifier),
        enabled = enabled,
        interactionSource = press.interactionSource,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonLabel(label)
    }
}

/** Displays a text action with a bounded label and press feedback. */
@Composable
internal fun OtohaTextButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val press = rememberButtonPress()
    TextButton(
        onClick = onClick,
        modifier = modifier.then(press.modifier),
        enabled = enabled,
        interactionSource = press.interactionSource,
    ) {
        ButtonLabel(label)
    }
}

/** Displays an icon action with press feedback. */
@Composable
internal fun OtohaIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val press = rememberButtonPress()
    IconButton(
        onClick = onClick,
        modifier = press.modifier,
        enabled = enabled,
        interactionSource = press.interactionSource,
        content = content,
    )
}

/** Displays a selectable filter with a single-line label. */
@Composable
internal fun OtohaFilterChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val press = rememberButtonPress()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { ButtonLabel(label, maxLines = 1) },
        modifier = modifier.then(press.modifier),
        interactionSource = press.interactionSource,
    )
}

/** Displays a facet action with a single-line label. */
@Composable
internal fun OtohaAssistChip(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val press = rememberButtonPress()
    AssistChip(
        onClick = onClick,
        label = { ButtonLabel(label, maxLines = 1) },
        modifier = modifier.then(press.modifier),
        interactionSource = press.interactionSource,
    )
}
