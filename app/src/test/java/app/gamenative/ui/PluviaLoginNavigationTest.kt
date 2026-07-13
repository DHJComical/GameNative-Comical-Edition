package app.gamenative.ui

import app.gamenative.ui.screen.PluviaScreen
import org.junit.Assert.assertEquals
import org.junit.Test

class PluviaLoginNavigationTest {
    private val loginRoute = PluviaScreen.LoginUser.route
    private val homeRoute = PluviaScreen.Home.route
    private val homeDestinationRoute = "$homeRoute?offline={offline}"

    @Test
    fun `successful login returns to an existing home entry`() {
        assertEquals(
            LoginNavigationAction.RETURN_TO_EXISTING_HOME,
            loginNavigationAction(
                currentRoute = loginRoute,
                previousRoute = homeDestinationRoute,
                targetRoute = "$homeRoute?offline=false",
            ),
        )
    }

    @Test
    fun `first login creates a new home entry`() {
        assertEquals(
            LoginNavigationAction.NAVIGATE_TO_TARGET,
            loginNavigationAction(
                currentRoute = loginRoute,
                previousRoute = null,
                targetRoute = homeRoute,
            ),
        )
    }

    @Test
    fun `persisted non-home destinations keep their existing navigation behavior`() {
        assertEquals(
            LoginNavigationAction.NAVIGATE_TO_TARGET,
            loginNavigationAction(
                currentRoute = loginRoute,
                previousRoute = homeDestinationRoute,
                targetRoute = PluviaScreen.Settings.route,
            ),
        )
    }

    @Test
    fun `navigation events outside login are ignored`() {
        assertEquals(
            LoginNavigationAction.IGNORE,
            loginNavigationAction(
                currentRoute = homeDestinationRoute,
                previousRoute = null,
                targetRoute = homeRoute,
            ),
        )
    }

    @Test
    fun `background login keeps the existing home entry`() {
        assertEquals(
            LoginNavigationAction.IGNORE,
            loginNavigationAction(
                currentRoute = homeDestinationRoute,
                previousRoute = null,
                targetRoute = "$homeRoute?offline=false",
            ),
        )
    }

    @Test
    fun `requested offline mode ends after Steam login without replacing home`() {
        assertEquals(true, effectiveHomeOffline(requestedOffline = true, isSteamLoggedIn = false))
        assertEquals(false, effectiveHomeOffline(requestedOffline = true, isSteamLoggedIn = true))
    }

    @Test
    fun `explicit online route remains online while Steam is disconnected`() {
        assertEquals(false, effectiveHomeOffline(requestedOffline = false, isSteamLoggedIn = false))
    }

    @Test
    fun `go online opens login only when Steam is not logged in`() {
        assertEquals(true, shouldOpenLoginForGoOnline(isSteamLoggedIn = false))
        assertEquals(false, shouldOpenLoginForGoOnline(isSteamLoggedIn = true))
    }
}
