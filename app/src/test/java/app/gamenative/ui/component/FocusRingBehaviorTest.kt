package app.gamenative.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusRingBehaviorTest {
    @Test
    fun focusedGradientUsesRotationAnimation() {
        assertTrue(shouldAnimateFocusRing(focused = true, hasSolidColor = false))
    }

    @Test
    fun solidFocusRingNeverUsesRotationAnimation() {
        assertFalse(shouldAnimateFocusRing(focused = true, hasSolidColor = true))
        assertFalse(shouldAnimateFocusRing(focused = false, hasSolidColor = true))
    }

    @Test
    fun unfocusedGradientDoesNotUseRotationAnimation() {
        assertFalse(shouldAnimateFocusRing(focused = false, hasSolidColor = false))
    }
}
