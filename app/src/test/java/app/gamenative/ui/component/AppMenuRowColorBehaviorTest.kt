package app.gamenative.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMenuRowColorBehaviorTest {
    @Test
    fun idleDefaultRowKeepsNormalContentColor() {
        assertFalse(
            shouldUseAppMenuRowInteractionContentColor(
                variant = AppMenuRowVariant.Default,
                focused = false,
                active = false,
                hasInteractionColor = true,
            ),
        )
    }

    @Test
    fun customDefaultColorAppliesOnlyWhileFocusedOrActive() {
        assertTrue(
            shouldUseAppMenuRowInteractionContentColor(
                variant = AppMenuRowVariant.Default,
                focused = true,
                active = false,
                hasInteractionColor = true,
            ),
        )
        assertTrue(
            shouldUseAppMenuRowInteractionContentColor(
                variant = AppMenuRowVariant.Default,
                focused = false,
                active = true,
                hasInteractionColor = true,
            ),
        )
        assertFalse(
            shouldUseAppMenuRowInteractionContentColor(
                variant = AppMenuRowVariant.Default,
                focused = true,
                active = false,
                hasInteractionColor = false,
            ),
        )
    }

    @Test
    fun accentAndDestructiveRowsAlwaysUseTheirSemanticColor() {
        assertTrue(alwaysIdleUsesInteractionColor(AppMenuRowVariant.Accent))
        assertTrue(alwaysIdleUsesInteractionColor(AppMenuRowVariant.Destructive))
    }

    private fun alwaysIdleUsesInteractionColor(variant: AppMenuRowVariant): Boolean =
        shouldUseAppMenuRowInteractionContentColor(
            variant = variant,
            focused = false,
            active = false,
            hasInteractionColor = false,
        )
}
