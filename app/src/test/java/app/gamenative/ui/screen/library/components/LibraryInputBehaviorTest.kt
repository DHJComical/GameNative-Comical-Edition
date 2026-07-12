package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryInputBehaviorTest {
    @Test
    fun `search actions meet minimum touch target`() {
        assertTrue(LibrarySearchActionTouchTargetSize >= 48.dp)
    }

    @Test
    fun `dpad down hands focus to compose`() {
        var handedToCompose = false

        val consumed = handleSearchInputKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
            moveFocusDown = {
                handedToCompose = true
                true
            },
        )

        assertTrue(consumed)
        assertTrue(handedToCompose)
    }

    @Test
    fun `other search input keys remain available to edit text`() {
        var handedToCompose = false

        val consumed = handleSearchInputKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_A,
            moveFocusDown = {
                handedToCompose = true
                true
            },
        )

        assertFalse(consumed)
        assertFalse(handedToCompose)
    }

    @Test
    fun `dpad down remains unconsumed when compose cannot move focus`() {
        val consumed = handleSearchInputKey(
            action = KeyEvent.ACTION_DOWN,
            keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
            moveFocusDown = { false },
        )

        assertFalse(consumed)
    }

    @Test
    fun `skeleton blocks input throughout fade out`() {
        assertTrue(shouldBlockLibraryInput(1f))
        assertTrue(shouldBlockLibraryInput(0.01f))
        assertFalse(shouldBlockLibraryInput(0f))
    }
}
