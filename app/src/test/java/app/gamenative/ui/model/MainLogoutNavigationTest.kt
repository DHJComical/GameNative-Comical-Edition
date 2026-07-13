package app.gamenative.ui.model

import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainLogoutNavigationTest {
    @Test
    fun `explicit logout and rejected credentials require login navigation`() {
        assertTrue(MainViewModel.requiresLoginNavigation(SteamEvent.LogoutReason.USER_REQUEST))
        assertTrue(MainViewModel.requiresLoginNavigation(SteamEvent.LogoutReason.CREDENTIALS_REJECTED))
    }

    @Test
    fun `connection loss and service stop preserve current page`() {
        assertFalse(MainViewModel.requiresLoginNavigation(SteamEvent.LogoutReason.CONNECTION_LOST))
        assertFalse(MainViewModel.requiresLoginNavigation(SteamEvent.LogoutReason.SERVICE_STOPPED))
    }

    @Test
    fun `transient disconnect preserves login but terminal disconnect clears it`() {
        assertTrue(MainViewModel.loggedInAfterDisconnect(currentlyLoggedIn = true, isTerminal = false))
        assertFalse(MainViewModel.loggedInAfterDisconnect(currentlyLoggedIn = true, isTerminal = true))
    }

    @Test
    fun `terminal network loss reports session end without requiring navigation`() {
        assertEquals(
            SteamEvent.LogoutReason.CONNECTION_LOST,
            SteamService.terminalLogoutReason(
                isStopping = false,
                isLoggingOut = false,
                hadLoggedInSession = true,
            ),
        )
    }

    @Test
    fun `service stop reports session end without requiring navigation`() {
        assertEquals(
            SteamEvent.LogoutReason.SERVICE_STOPPED,
            SteamService.terminalLogoutReason(
                isStopping = true,
                isLoggingOut = false,
                hadLoggedInSession = true,
            ),
        )
    }

    @Test
    fun `explicit logout and unauthenticated disconnect do not duplicate logout`() {
        assertNull(
            SteamService.terminalLogoutReason(
                isStopping = true,
                isLoggingOut = true,
                hadLoggedInSession = true,
            ),
        )
        assertNull(
            SteamService.terminalLogoutReason(
                isStopping = false,
                isLoggingOut = false,
                hadLoggedInSession = false,
            ),
        )
    }
}
