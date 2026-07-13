package app.gamenative.ui.model

import app.gamenative.data.SteamApp
import app.gamenative.enums.AppType
import app.gamenative.ui.enums.AppFilter
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamAppTypeFilterTest {
    @Test
    fun `filters Steam entries using selected Library app types`() {
        val apps = listOf(
            steamApp(1, AppType.game),
            steamApp(2, AppType.application),
            steamApp(3, AppType.tool),
            steamApp(4, AppType.demo),
            steamApp(5, AppType.dlc),
        )

        val result = filterSteamAppsByType(
            apps = apps,
            filters = EnumSet.of(AppFilter.GAME, AppFilter.TOOL, AppFilter.SHARED),
        )

        assertEquals(listOf(1, 3), result.map(SteamApp::id))
    }

    @Test
    fun `reflects a changed Library app type selection`() {
        val apps = listOf(steamApp(1, AppType.game), steamApp(2, AppType.demo))

        val before = filterSteamAppsByType(apps, EnumSet.of(AppFilter.GAME))
        val after = filterSteamAppsByType(apps, EnumSet.of(AppFilter.DEMO))

        assertEquals(listOf(1), before.map(SteamApp::id))
        assertEquals(listOf(2), after.map(SteamApp::id))
    }

    @Test
    fun `keeps an installed manifest stub before PICS metadata arrives`() {
        val stub = SteamApp(id = 7, name = "Offline game", type = AppType.invalid, receivedPICS = false)

        val result = filterSteamAppsByType(listOf(stub), EnumSet.of(AppFilter.GAME))

        assertEquals(listOf(7), result.map(SteamApp::id))
    }

    @Test
    fun `does not treat a PICS invalid type as a game`() {
        val invalid = SteamApp(id = 8, type = AppType.invalid, receivedPICS = true)

        val result = filterSteamAppsByType(listOf(invalid), EnumSet.of(AppFilter.GAME))

        assertEquals(emptyList<SteamApp>(), result)
    }

    private fun steamApp(id: Int, type: AppType) = SteamApp(id = id, type = type)
}
