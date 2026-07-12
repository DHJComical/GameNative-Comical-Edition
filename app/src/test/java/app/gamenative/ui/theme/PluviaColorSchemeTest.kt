package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.PaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluviaColorSchemeTest {
    @Test
    fun lightAndDarkModesProduceMatchingSurfaceLuminance() {
        val light = createPluviaColorScheme(PluviaSeed, isDark = false, isAmoled = false, style = PaletteStyle.TonalSpot)
        val dark = createPluviaColorScheme(PluviaSeed, isDark = true, isAmoled = false, style = PaletteStyle.TonalSpot)

        assertTrue(light.background.luminance() > dark.background.luminance())
        assertTrue(light.onBackground.luminance() < dark.onBackground.luminance())
    }

    @Test
    fun amoledModeUsesBlackLowestSurfaces() {
        val amoled = createPluviaColorScheme(PluviaSeed, isDark = true, isAmoled = true, style = PaletteStyle.TonalSpot)

        assertEquals(Color.Black, amoled.background)
        assertEquals(Color.Black, amoled.surface)
        assertEquals(Color.Black, amoled.surfaceDim)
        assertEquals(Color.Black, amoled.surfaceContainerLowest)
        assertNotEquals(Color.Black, amoled.surfaceContainerLow)
        assertTrue(contrastRatio(amoled.onBackground, amoled.background) >= 4.5f)
        assertTrue(contrastRatio(amoled.onSurface, amoled.surface) >= 4.5f)
    }

    @Test
    fun paletteStyleChangesGeneratedPalette() {
        val tonal = createPluviaColorScheme(PluviaSeed, isDark = true, isAmoled = false, style = PaletteStyle.TonalSpot)
        val expressive = createPluviaColorScheme(PluviaSeed, isDark = true, isAmoled = false, style = PaletteStyle.Expressive)

        assertNotEquals(tonal.primary, expressive.primary)
        assertNotEquals(tonal.tertiary, expressive.tertiary)
    }

    @Test
    fun seedColorChangesGeneratedPalette() {
        val purple = createPluviaColorScheme(Color(0xFF6750A4), isDark = true, isAmoled = false, style = PaletteStyle.TonalSpot)
        val green = createPluviaColorScheme(Color(0xFF006E1C), isDark = true, isAmoled = false, style = PaletteStyle.TonalSpot)

        assertNotEquals(purple.primary, green.primary)
        assertNotEquals(purple.primaryContainer, green.primaryContainer)
    }

    @Test
    fun applicationSurfacesFollowMaterialScheme() {
        val scheme = createPluviaColorScheme(PluviaSeed, isDark = false, isAmoled = false, style = PaletteStyle.Vibrant)
        val colors = createPluviaColors(scheme)

        assertEquals(scheme.surfaceContainer, colors.surfacePanel)
        assertEquals(scheme.surfaceContainerHigh, colors.surfaceElevated)
        assertEquals(scheme.onSurfaceVariant, colors.textMuted)
        assertEquals(scheme.outline, colors.borderDefault)
    }

    @Test
    fun pageForegroundsRemainReadableInLightAndDarkSchemes() {
        listOf(false, true).forEach { isDark ->
            val scheme = createPluviaColorScheme(
                seedColor = PluviaSeed,
                isDark = isDark,
                isAmoled = false,
                style = PaletteStyle.TonalSpot,
            )

            assertTrue(contrastRatio(scheme.onBackground, scheme.background) >= 4.5f)
            assertTrue(contrastRatio(scheme.onSurface, scheme.surface) >= 4.5f)
            assertTrue(contrastRatio(scheme.onSurfaceVariant, scheme.surfaceContainerHigh) >= 4.5f)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
