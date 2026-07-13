package app.gamenative.ui.model

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.gamenative.PrefManager
import app.gamenative.ui.data.HomeState
import app.gamenative.ui.enums.HomeDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private companion object {
        const val KEY_HOME_DESTINATION = "home_destination"
    }

    private val initialDestination = savedStateHandle.get<String>(KEY_HOME_DESTINATION)
        ?.let(HomeDestination::valueOf)
        ?: PrefManager.startScreen
    private val _homeState = MutableStateFlow(HomeState(currentDestination = initialDestination))
    val homeState: StateFlow<HomeState> = _homeState.asStateFlow()

    fun onDestination(destination: HomeDestination) {
        savedStateHandle[KEY_HOME_DESTINATION] = destination.name
        _homeState.update { currentState ->
            currentState.copy(currentDestination = destination)
        }
    }

    fun onConfirmExit(value: Boolean) {
        _homeState.update { currentState ->
            currentState.copy(confirmExit = value)
        }
    }
}
