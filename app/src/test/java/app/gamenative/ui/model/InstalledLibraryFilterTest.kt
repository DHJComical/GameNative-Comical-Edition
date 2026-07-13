package app.gamenative.ui.model

import app.gamenative.data.GameSource
import app.gamenative.ui.enums.AppFilter
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledLibraryFilterTest {
    @Test
    fun persistedInstallStatusFiltersAreRemoved() {
        val filters = EnumSet.of(AppFilter.GAME, AppFilter.INSTALLED, AppFilter.EXPIRED)

        val sanitized = sanitizeInstalledLibraryFilters(filters)

        assertEquals(EnumSet.of(AppFilter.GAME), sanitized)
        assertTrue(filters.contains(AppFilter.INSTALLED))
    }

    @Test
    fun defaultGameAndSharedFiltersIncludeEveryInstalledOwnershipState() {
        val filters = EnumSet.of(AppFilter.GAME, AppFilter.SHARED)
        val includeShared = filters.contains(AppFilter.SHARED)

        assertTrue(shouldIncludeInstalledSteamApp(listOf(42), currentAccountId = 42, includeShared))
        assertTrue(shouldIncludeInstalledSteamApp(listOf(7), currentAccountId = 42, includeShared))
        assertTrue(shouldIncludeInstalledSteamApp(emptyList(), currentAccountId = 42, includeShared))
    }

    @Test
    fun disablingSharedFilterExcludesOnlyKnownOtherAccountOwnership() {
        assertTrue(shouldIncludeInstalledSteamApp(listOf(42), currentAccountId = 42, includeShared = false))
        assertTrue(shouldIncludeInstalledSteamApp(emptyList(), currentAccountId = 42, includeShared = false))
        assertTrue(shouldIncludeInstalledSteamApp(listOf(7), currentAccountId = 0, includeShared = false))
        assertFalse(shouldIncludeInstalledSteamApp(listOf(7), currentAccountId = 42, includeShared = false))
    }

    @Test
    fun roomBackedInstallEventsDoNotTriggerLegacyListFiltering() {
        assertFalse(shouldRefreshInstalledLibraryForEvent(GameSource.STEAM))
        assertFalse(shouldRefreshInstalledLibraryForEvent(GameSource.GOG))
        assertFalse(shouldRefreshInstalledLibraryForEvent(GameSource.EPIC))
        assertFalse(shouldRefreshInstalledLibraryForEvent(GameSource.AMAZON))
    }

    @Test
    fun customInstallEventRefreshesScannerBackedLibrary() {
        assertTrue(shouldRefreshInstalledLibraryForEvent(GameSource.CUSTOM_GAME))
    }
}
