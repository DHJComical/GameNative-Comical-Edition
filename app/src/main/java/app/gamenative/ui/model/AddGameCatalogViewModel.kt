package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.enums.AppFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.EnumSet
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** Store tabs shown by the add-game catalog. */
enum class AddGameStore(val gameSource: GameSource?) {
    STEAM(GameSource.STEAM),
    GOG(GameSource.GOG),
    EPIC(GameSource.EPIC),
    AMAZON(GameSource.AMAZON),
    LOCAL_FOLDER(null),
}

/** Current state of the add-game catalog sheet. */
data class AddGameCatalogState(
    val isOpen: Boolean = false,
    val selectedStore: AddGameStore = AddGameStore.STEAM,
    val items: List<LibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val requiresLogin: Boolean = false,
    val error: String? = null,
)

internal fun AddGameCatalogState.withCatalogItems(
    store: AddGameStore,
    catalogItems: List<LibraryItem>,
): AddGameCatalogState = if (isOpen && selectedStore == store) {
    copy(items = catalogItems)
} else {
    this
}

internal fun AddGameCatalogState.withRefreshSuccess(store: AddGameStore): AddGameCatalogState =
    if (isOpen && selectedStore == store) copy(isLoading = false, error = null) else this

internal fun AddGameCatalogState.withRefreshFailure(
    store: AddGameStore,
    message: String,
): AddGameCatalogState = if (isOpen && selectedStore == store) {
    copy(isLoading = false, error = message)
} else {
    this
}

/**
 * Owns the catalog subscription used by the add-game sheet.
 *
 * Only the selected store is observed and refreshed. Closing the sheet cancels
 * both its database collector and its in-flight refresh.
 */
@HiltViewModel
class AddGameCatalogViewModel @Inject constructor(
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(AddGameCatalogState())
    val state: StateFlow<AddGameCatalogState> = _state.asStateFlow()

    private val _loginRequests = MutableSharedFlow<AddGameStore>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val loginRequests: SharedFlow<AddGameStore> = _loginRequests.asSharedFlow()

    private val _localFolderRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localFolderRequests: SharedFlow<Unit> = _localFolderRequests.asSharedFlow()

    private var catalogJob: Job? = null
    private var refreshJob: Job? = null
    private var authenticationJob: Job? = null
    private var steamCatalogGeneration: Long? = null
    private var refreshSession = 0L
    private val steamAppTypeFilters = MutableStateFlow(EnumSet.copyOf(PrefManager.libraryFilter))

    /** Keeps the Steam catalog aligned with the app-type choices in Library options. */
    fun updateSteamAppTypeFilters(filters: EnumSet<AppFilter>) {
        steamAppTypeFilters.value = EnumSet.copyOf(filters)
    }

    fun open() {
        _state.value = AddGameCatalogState(isOpen = true)
        observeSelectedStore()
    }

    fun close() {
        refreshSession++
        catalogJob?.cancel()
        refreshJob?.cancel()
        authenticationJob?.cancel()
        endSteamCatalogSession()
        catalogJob = null
        refreshJob = null
        authenticationJob = null
        _state.value = AddGameCatalogState()
    }

    fun selectStore(store: AddGameStore) {
        if (!_state.value.isOpen) return
        if (store == AddGameStore.LOCAL_FOLDER) {
            refreshSession++
            catalogJob?.cancel()
            refreshJob?.cancel()
            authenticationJob?.cancel()
            endSteamCatalogSession()
            _state.update {
                it.copy(selectedStore = store, items = emptyList(), isLoading = false, requiresLogin = false, error = null)
            }
            _localFolderRequests.tryEmit(Unit)
            return
        }
        if (_state.value.selectedStore == store && catalogJob?.isActive == true) return
        _state.update { it.copy(selectedStore = store, items = emptyList(), error = null) }
        observeSelectedStore()
    }

    fun refresh() {
        if (!_state.value.isOpen || _state.value.selectedStore == AddGameStore.LOCAL_FOLDER) return
        startRefresh(_state.value.selectedStore)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSelectedStore() {
        catalogJob?.cancel()
        refreshJob?.cancel()
        authenticationJob?.cancel()
        endSteamCatalogSession()
        val store = _state.value.selectedStore
        if (!isAuthenticated(store)) {
            _state.update { it.copy(items = emptyList(), isLoading = false, requiresLogin = true, error = null) }
            _loginRequests.tryEmit(store)
            if (store == AddGameStore.STEAM) {
                authenticationJob = viewModelScope.launch {
                    while (_state.value.isOpen && _state.value.selectedStore == store && !SteamService.isLoggedIn) {
                        delay(250L)
                    }
                    if (_state.value.isOpen && _state.value.selectedStore == store && SteamService.isLoggedIn) {
                        authenticationJob = null
                        observeSelectedStore()
                    }
                }
            }
            return
        }
        _state.update { it.copy(items = emptyList(), isLoading = true, requiresLogin = false, error = null) }
        catalogJob = viewModelScope.launch(Dispatchers.IO) {
            catalogFlow(store)
                .catch { error -> handleFailure(store, error) }
                .collectLatest { items ->
                    _state.update { it.withCatalogItems(store, items) }
                }
        }
        startRefresh(store)
    }

    private fun startRefresh(store: AddGameStore) {
        refreshJob?.cancel()
        val session = ++refreshSession
        _state.update { it.copy(isLoading = true, error = null) }
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                when (store) {
                    AddGameStore.STEAM -> {
                        val generation = SteamService.beginCatalogSession()
                        steamCatalogGeneration = generation
                        try {
                            SteamService.refreshOwnedGamesFromServer()
                            SteamService.awaitCatalogSession(generation)
                        } finally {
                            SteamService.endCatalogSession(generation)
                            if (steamCatalogGeneration == generation) {
                                steamCatalogGeneration = null
                            }
                        }
                    }
                    AddGameStore.GOG -> {
                        GOGService.startForDownloadRecovery(context)
                        awaitServiceReady(GOGService::isRunning)
                        GOGService.refreshLibrary(context).getOrThrow()
                    }
                    AddGameStore.EPIC -> {
                        EpicService.startForDownloadRecovery(context)
                        awaitServiceReady(EpicService::isRunning)
                        EpicService.refreshLibrary(context).getOrThrow()
                    }
                    AddGameStore.AMAZON -> {
                        AmazonService.startForDownloadRecovery(context)
                        awaitServiceReady(AmazonService::isRunning)
                        AmazonService.refreshLibrary().getOrThrow()
                    }
                    AddGameStore.LOCAL_FOLDER -> Unit
                }
                if (refreshSession == session) {
                    _state.update { it.withRefreshSuccess(store) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleFailure(store, error, session)
            }
        }
    }

    private fun endSteamCatalogSession() {
        steamCatalogGeneration?.let(SteamService::endCatalogSession)
        steamCatalogGeneration = null
    }

    private suspend fun awaitServiceReady(isReady: () -> Boolean) {
        withTimeout(5_000L) {
            while (!isReady()) delay(50L)
        }
    }

    override fun onCleared() {
        endSteamCatalogSession()
        super.onCleared()
    }

    private fun isAuthenticated(store: AddGameStore): Boolean = when (store) {
        AddGameStore.STEAM -> SteamService.isLoggedIn
        AddGameStore.GOG -> GOGService.hasStoredCredentials(context)
        AddGameStore.EPIC -> EpicService.hasStoredCredentials(context)
        AddGameStore.AMAZON -> AmazonService.hasStoredCredentials(context)
        AddGameStore.LOCAL_FOLDER -> true
    }

    private fun catalogFlow(store: AddGameStore): Flow<List<LibraryItem>> = when (store) {
        AddGameStore.STEAM -> steamAppDao.getAllOwnedApps()
            .combine(steamAppTypeFilters) { apps, filters -> filterSteamAppsByType(apps, filters) }
            .map { apps ->
                apps.mapIndexed { index, app ->
                    LibraryItem(
                        index = index,
                        appId = "${GameSource.STEAM.name}_${app.id}",
                        name = app.name,
                        iconHash = app.clientIconHash,
                        capsuleImageUrl = app.getCapsuleUrl(),
                        headerImageUrl = app.headerUrl,
                        heroImageUrl = app.getHeroUrl(),
                    )
                }
            }
        AddGameStore.GOG -> gogGameDao.getAll().map { games ->
            games.mapIndexed { index, game ->
                LibraryItem(
                    index = index,
                    appId = "${GameSource.GOG.name}_${game.id}",
                    name = game.title,
                    iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                    capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                    headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                    heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                    gameSource = GameSource.GOG,
                    isInstalled = game.isInstalled,
                )
            }
        }
        AddGameStore.EPIC -> epicGameDao.getAll().map { games ->
            games.mapIndexed { index, game ->
                LibraryItem(
                    index = index,
                    appId = "${GameSource.EPIC.name}_${game.id}",
                    name = game.title,
                    iconHash = game.artSquare.ifEmpty { game.artCover },
                    capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                    headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                    heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                    gameSource = GameSource.EPIC,
                    isInstalled = game.isInstalled,
                )
            }
        }
        AddGameStore.AMAZON -> amazonGameDao.getAll().map { games ->
            games.mapIndexed { index, game ->
                val hero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                    .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
                LibraryItem(
                    index = index,
                    appId = "${GameSource.AMAZON.name}_${game.appId}",
                    name = game.title,
                    iconHash = game.artUrl,
                    capsuleImageUrl = game.artUrl,
                    headerImageUrl = hero,
                    heroImageUrl = hero.ifEmpty { game.artUrl },
                    gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                    gameSource = GameSource.AMAZON,
                    isInstalled = game.isInstalled,
                )
            }
        }
        AddGameStore.LOCAL_FOLDER -> emptyFlow()
    }

    private fun handleFailure(store: AddGameStore, error: Throwable, session: Long? = null) {
        Timber.e(error, "Failed to load %s add-game catalog", store)
        if (store == AddGameStore.STEAM) endSteamCatalogSession()
        if (session == null || refreshSession == session) {
            _state.update {
                it.withRefreshFailure(store, error.message ?: "Failed to load catalog")
            }
        }
    }
}
