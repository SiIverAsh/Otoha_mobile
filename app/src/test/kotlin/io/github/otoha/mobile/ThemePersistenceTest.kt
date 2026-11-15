package io.github.otoha.mobile

import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.otoha.core.SourceId
import io.github.otoha.core.WorkRef
import io.github.otoha.core.WorkSummary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
    }

    @Test fun `old version personal data defaults to system theme and preserves existing settings`() {
        val context = context()
        File(context.filesDir, "personal-data.json").writeText("""{"version":1,"showRestricted":true,"favorites":[]}""")
        val data = AppStore(context).data.value
        assertEquals(ThemeMode.SYSTEM, data.themeMode)
        assertTrue(data.showRestricted)
    }

    @Test fun `theme changes survive new store instance without deleting favorites or age settings`() {
        val context = context()
        val work = WorkSummary(WorkRef(SourceId.ASMR_ONE, "123456"), "旧收藏")
        val first = AppStore(context)
        first.toggleFavorite(work)
        first.setRestricted(true)
        first.setThemeMode(ThemeMode.DARK)
        val dark = AppStore(context)
        assertEquals(ThemeMode.DARK, dark.data.value.themeMode)
        assertEquals(listOf(work), dark.data.value.favorites)
        assertTrue(dark.data.value.showRestricted)
        assertTrue(dark.data.value.favorites.single().facets.isEmpty())
        dark.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, AppStore(context).data.value.themeMode)
    }

    @Test fun `explicit themes override system and dark surface roles retain readable contrast`() {
        assertTrue(ThemeMode.SYSTEM.usesDarkColors(true))
        assertFalse(ThemeMode.SYSTEM.usesDarkColors(false))
        assertTrue(ThemeMode.DARK.usesDarkColors(false))
        assertFalse(ThemeMode.LIGHT.usesDarkColors(true))
        val dark = otohaColors(true)
        assertNotEquals(otohaColors(false).background, dark.background)
        assertTrue(dark.background.luminance() < .05f)
        assertTrue(contrast(dark.onBackground, dark.background) >= 4.5f)
        assertTrue(contrast(dark.onSurface, dark.surfaceContainer) >= 4.5f)
        assertTrue(contrast(dark.onPrimaryContainer, dark.primaryContainer) >= 4.5f)
    }

    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
}
