package io.github.otoha.mobile

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK;
    fun usesDarkColors(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF6962AA), secondary = Color(0xFF73718A),
    background = Color(0xFFF8F7FC), surface = Color(0xFFF8F7FC),
    surfaceContainer = Color(0xFFFFFFFF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFC9BDFF), onPrimary = Color(0xFF30284F),
    primaryContainer = Color(0xFF473D68), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFC7C0DC), onSecondary = Color(0xFF302C40),
    background = Color(0xFF121116), onBackground = Color(0xFFE9E3EE),
    surface = Color(0xFF121116), onSurface = Color(0xFFE9E3EE),
    surfaceContainer = Color(0xFF211E28), surfaceVariant = Color(0xFF49444F),
    onSurfaceVariant = Color(0xFFCEC6D4), outline = Color(0xFF988F9E),
)

internal fun otohaColors(dark: Boolean) = if (dark) DarkColors else LightColors

@Composable
internal fun OtohaTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.usesDarkColors(isSystemInDarkTheme())
    val activity = LocalActivity.current as? ComponentActivity
    SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(0xE6FFFFFF.toInt(), 0x801B1B1B.toInt()) { dark },
        )
    }
    MaterialTheme(colorScheme = otohaColors(dark), content = content)
}
