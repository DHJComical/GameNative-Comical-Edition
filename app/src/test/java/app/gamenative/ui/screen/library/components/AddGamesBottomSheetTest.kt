package app.gamenative.ui.screen.library.components

import app.gamenative.ui.model.AddGameStore
import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddGamesBottomSheetTest {
    @Test
    fun `drag handle keeps the material marker while reducing its header height`() {
        assertEquals(32.dp, addGamesDragHandleWidth)
        assertEquals(4.dp, addGamesDragHandleHeight)
        assertEquals(8.dp, addGamesDragHandleVerticalPadding)
        assertEquals(
            20.dp,
            addGamesDragHandleHeight + addGamesDragHandleVerticalPadding * 2,
        )
    }

    @Test
    fun `sheet uses most of the viewport without exceeding compact windows`() {
        assertEquals(656.dp, addGamesSheetHeight(800))
        assertEquals(147.6.dp, addGamesSheetHeight(180))
        assertEquals(0.dp, addGamesSheetHeight(0))
    }

    @Test
    fun `store navigation wraps without an all tab`() {
        assertEquals(AddGameStore.AMAZON, nextAddGameStore(AddGameStore.LOCAL_FOLDER, -1))
        assertEquals(AddGameStore.STEAM, nextAddGameStore(AddGameStore.LOCAL_FOLDER, 1))
        assertEquals(AddGameStore.LOCAL_FOLDER, nextAddGameStore(AddGameStore.STEAM, -1))
    }

    @Test
    fun `controller B dismisses the sheet and consumes only key down`() {
        var dismissCount = 0
        val onDismiss = { dismissCount += 1 }

        assertFalse(
            handleAddGamesSheetKey(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                selectedStore = AddGameStore.STEAM,
                onStoreSelected = { },
                onDismiss = onDismiss,
            ),
        )
        assertTrue(
            handleAddGamesSheetKey(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                selectedStore = AddGameStore.STEAM,
                onStoreSelected = { },
                onDismiss = onDismiss,
            ),
        )
        assertEquals(1, dismissCount)
    }

    @Test
    fun `initial game focus never overrides user interaction or a completed request`() {
        assertTrue(
            shouldRequestInitialAddGameFocus(
                hasItems = true,
                initialFocusRequested = false,
                userInteracted = false,
            ),
        )
        assertFalse(
            shouldRequestInitialAddGameFocus(
                hasItems = true,
                initialFocusRequested = false,
                userInteracted = true,
            ),
        )
        assertFalse(
            shouldRequestInitialAddGameFocus(
                hasItems = true,
                initialFocusRequested = true,
                userInteracted = false,
            ),
        )
    }

    @Test
    fun `sheet root owns focus whenever the game grid is unavailable`() {
        assertTrue(
            shouldFocusAddGamesSheetRoot(
                requiresLogin = false,
                hasError = false,
                hasItems = false,
            ),
        )
        assertTrue(
            shouldFocusAddGamesSheetRoot(
                requiresLogin = true,
                hasError = false,
                hasItems = false,
            ),
        )
        assertTrue(
            shouldFocusAddGamesSheetRoot(
                requiresLogin = false,
                hasError = true,
                hasItems = true,
            ),
        )
        assertFalse(
            shouldFocusAddGamesSheetRoot(
                requiresLogin = false,
                hasError = false,
                hasItems = true,
            ),
        )
    }

    @Test
    fun `controller navigation remains available after a populated store becomes empty`() {
        assertFalse(
            shouldFocusAddGamesSheetRoot(
                requiresLogin = false,
                hasError = false,
                hasItems = true,
            ),
        )
        assertTrue(
            shouldFocusAddGamesSheetRoot(
                requiresLogin = false,
                hasError = false,
                hasItems = false,
            ),
        )

        var selectedStore = AddGameStore.STEAM
        assertTrue(
            handleAddGamesSheetKey(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_BUTTON_R1,
                selectedStore = selectedStore,
                onStoreSelected = { selectedStore = it },
                onDismiss = { },
            ),
        )
        assertEquals(AddGameStore.GOG, selectedStore)
    }
}
