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
}
