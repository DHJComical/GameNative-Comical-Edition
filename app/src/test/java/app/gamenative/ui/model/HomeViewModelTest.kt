package app.gamenative.ui.model

import androidx.lifecycle.SavedStateHandle
import app.gamenative.PrefManager
import app.gamenative.ui.enums.HomeDestination
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeViewModelTest {
    @Test
    fun usesPreferredStartScreenWhenSavedStateIsEmpty() {
        mockkObject(PrefManager)
        try {
            every { PrefManager.startScreen } returns HomeDestination.Storage

            val viewModel = HomeViewModel(SavedStateHandle())

            assertEquals(HomeDestination.Storage, viewModel.homeState.value.currentDestination)
        } finally {
            unmockkObject(PrefManager)
        }
    }

    @Test
    fun unknownSavedDestinationFailsFast() {
        val savedStateHandle = SavedStateHandle(
            mapOf("home_destination" to "RemovedDestination"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            HomeViewModel(savedStateHandle)
        }
    }

    @Test
    fun restoresDestinationFromSavedState() {
        val savedStateHandle = SavedStateHandle(
            mapOf("home_destination" to HomeDestination.GameLibraries.name),
        )

        val viewModel = HomeViewModel(savedStateHandle)

        assertEquals(HomeDestination.GameLibraries, viewModel.homeState.value.currentDestination)
    }

    @Test
    fun savesDestinationChanges() {
        val savedStateHandle = SavedStateHandle(
            mapOf("home_destination" to HomeDestination.Library.name),
        )
        val viewModel = HomeViewModel(savedStateHandle)

        viewModel.onDestination(HomeDestination.Downloads)

        assertEquals(HomeDestination.Downloads.name, savedStateHandle["home_destination"])
        assertEquals(HomeDestination.Downloads, viewModel.homeState.value.currentDestination)
    }
}
