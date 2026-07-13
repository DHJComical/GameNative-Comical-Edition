package app.gamenative.ui.screen.library

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGlobalInputTest {

    @Test
    fun libraryInteractionIsReportedOnlyAfterInitialLoadCompletes() {
        var interactionCount = 0

        assertFalse(reportLibraryInteractionIfReady(initialLoadComplete = false) { interactionCount++ })
        assertTrue(reportLibraryInteractionIfReady(initialLoadComplete = true) { interactionCount++ })
        assertEquals(1, interactionCount)
    }

    @Test
    fun rootPreviewKey_reportsOnlyKeyDown() {
        assertTrue(isLibraryKeyDown(KeyEvent.ACTION_DOWN))
        assertFalse(isLibraryKeyDown(KeyEvent.ACTION_UP))
        assertFalse(isLibraryKeyDown(KeyEvent.ACTION_MULTIPLE))
    }

    @Test
    fun rotaryReportsOnlyMotionAtOrAboveThreshold() {
        assertTrue(isLibraryRotaryMotion(verticalPixels = 0.5f, horizontalPixels = 0f))
        assertTrue(isLibraryRotaryMotion(verticalPixels = 0f, horizontalPixels = -0.5f))
        assertFalse(isLibraryRotaryMotion(verticalPixels = 0.499f, horizontalPixels = -0.499f))
        assertFalse(isLibraryRotaryMotion(verticalPixels = 0f, horizontalPixels = 0f))
    }

    @Test
    fun controllerNavigationKeyDown_isUserInteraction() {
        val interactionKeys = listOf(
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
        )

        interactionKeys.forEach { keyCode ->
            assertTrue(isLibraryGlobalControllerKey(KeyEvent.ACTION_DOWN, keyCode))
        }
    }

    @Test
    fun keyUpAndUnrelatedKey_areNotGlobalLibraryInteraction() {
        assertFalse(isLibraryGlobalControllerKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(isLibraryGlobalControllerKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun directionalMoveAboveThreshold_isUserInteraction() {
        assertTrue(isLibraryDirectionalMotion(MotionEvent.ACTION_MOVE, 0.5f, 0f, 0f, 0f))
        assertTrue(isLibraryDirectionalMotion(MotionEvent.ACTION_MOVE, 0f, 0f, 0f, -0.6f))
    }

    @Test
    fun idleAndNonMoveMotion_areNotUserInteraction() {
        assertFalse(isLibraryDirectionalMotion(MotionEvent.ACTION_MOVE, 0.49f, 0f, 0.59f, 0f))
        assertFalse(isLibraryDirectionalMotion(MotionEvent.ACTION_DOWN, 1f, 1f, 1f, 1f))
    }
}
