package app.gamenative.ui.model

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.SteamApp
import app.gamenative.events.AndroidEvent
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import app.gamenative.ui.enums.SortOption
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.DeviceGameStatsCache
import app.gamenative.utils.GpuGameStatsCache
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.unaccent
import com.winlator.core.GPUInformation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

private const val PLAYABLE_FPS_THRESHOLD = 30
private const val PROVEN_RUNS_THRESHOLD = 5

internal fun displayedLibraryTotal(gameCount: Int): Int = gameCount

internal fun sanitizeInstalledLibraryFilters(filters: EnumSet<AppFilter>): EnumSet<AppFilter> =
    EnumSet.copyOf(filters).apply {
        remove(AppFilter.INSTALLED)
        remove(AppFilter.EXPIRED)
    }

internal fun shouldIncludeInstalledSteamApp(
    ownerAccountIds: List<Int>,
    currentAccountId: Int,
    sharedOnly: Boolean,
): Boolean = !sharedOnly || ownerAccountIds.isNotEmpty() &&
    currentAccountId != 0 && !ownerAccountIds.contains(currentAccountId)

internal fun shouldRefreshInstalledLibraryForEvent(source: GameSource): Boolean =
    source == GameSource.CUSTOM_GAME

internal fun shouldShowBlockingLibraryLoading(initialLoadComplete: Boolean): Boolean =
    !initialLoadComplete


@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryPlayHistoryDao: LibraryPlayHistoryDao,
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LibraryState(
            appInfoSortType = sanitizeInstalledLibraryFilters(PrefManager.libraryFilter),
            isLoading = true,
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    private val onCustomGameImagesFetched: (AndroidEvent.CustomGameImagesFetched) -> Unit = {
        // Increment refresh counter and refresh the library list to pick up newly fetched images
        _state.update { it.copy(imageRefreshCounter = it.imageRefreshCounter + 1) }
        onFilterApps(paginationCurrentPage, FilterCommitKind.METADATA)
    }

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
        if (shouldRefreshInstalledLibraryForEvent(event.source)) {
            onFilterApps(paginationCurrentPage, FilterCommitKind.STRUCTURAL)
        }
    }

    // How many items loaded on one page of results
    @Volatile private var paginationCurrentPage: Int = 0
    @Volatile private var lastPageInCurrentFilter: Int = 0

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()
    private var gogGameList: List<GOGGame> = emptyList()
    private var epicGameList: List<EpicGame> = emptyList()
    private var amazonGameList: List<AmazonGame> = emptyList()
    private var playHistoryByAppId: Map<String, Long> = emptyMap()

    private val loadCoordinator = LibraryLoadCoordinator()
    private val filterJobLock = Any()
    private var filterJob: Job? = null

    // Track if this is the first load to apply minimum load time
    private var isFirstLoad = true

    // Track debounce job for search
    private var searchDebounceJob: Job? = null
    private val SEARCH_DEBOUNCE_MS = 500L // 500ms debounce

    // Cache GPU name to avoid repeated calls
    private val gpuName: String by lazy {
        try {
            val gpu = GPUInformation.getRenderer(context)
            if (gpu.isNullOrEmpty()) {
                Timber.tag("LibraryViewModel").w("GPU name is null or empty")
                "Unknown GPU"
            } else {
                Timber.tag("LibraryViewModel").d("Retrieved GPU name: $gpu")
                gpu
            }
        } catch (e: Exception) {
            Timber.tag("LibraryViewModel").e(e, "Failed to get GPU name")
            "Unknown GPU"
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (gpuName != "Unknown GPU") {
                DeviceGameStatsCache.refreshIfStale(
                    deviceModel = HardwareUtils.getMachineName(),
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
                GpuGameStatsCache.refreshIfStale(
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
            } else {
                Timber.tag("LibraryViewModel").w("Skipping device/GPU game stats fetch - GPU name is unknown")
            }
            _state.update {
                it.copy(
                    deviceGameStats = DeviceGameStatsCache.getAll(),
                    gpuGameStats = GpuGameStatsCache.getAll(),
                )
            }
            // Re-run filtering/sorting now that stats are available, if anything depends on them.
            if (usesStats(_state.value)) {
                onFilterApps(paginationCurrentPage, FilterCommitKind.STRUCTURAL)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            steamAppDao.observeInstalledGames()
                .catch { error ->
                    Timber.tag("LibraryViewModel").e(error, "Steam library source failed")
                    onLocalSourceEmission(LibraryLoadSource.STEAM)
                }
                .collect { apps ->
                    Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")
                    // Check if the list has actually changed before triggering a re-filter
                    val hasChanges = appList != apps
                    if (hasChanges) {
                        appList = apps
                    }
                    if (shouldProcessLibrarySourceEmission(hasChanges, loadCoordinator.isInitialSnapshotReady)) {
                        onLocalSourceEmission(LibraryLoadSource.STEAM)
                    }
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            libraryPlayHistoryDao.getAll()
                .catch { error ->
                    Timber.tag("LibraryViewModel").e(error, "Play history source failed")
                    onLocalSourceEmission(LibraryLoadSource.PLAY_HISTORY)
                }
                .collect { entries ->
                val playHistory = entries.associate { it.appId to it.lastPlayed }
                val hasChanges = playHistoryByAppId != playHistory
                if (hasChanges) {
                    playHistoryByAppId = playHistory
                }
                if (shouldProcessLibrarySourceEmission(hasChanges, loadCoordinator.isInitialSnapshotReady)) {
                    onLocalSourceEmission(LibraryLoadSource.PLAY_HISTORY)
                }
            }
        }

        // Collect GOG games
        viewModelScope.launch(Dispatchers.IO) {
            gogGameDao.observeInstalledGames()
                .catch { error ->
                    Timber.tag("LibraryViewModel").e(error, "GOG library source failed")
                    onLocalSourceEmission(LibraryLoadSource.GOG)
                }
                .collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} GOG games")
                // Check if the list has actually changed before triggering a re-filter
                    val hasChanges = gogGameList != games
                    if (hasChanges) {
                        gogGameList = games
                    }
                    if (shouldProcessLibrarySourceEmission(hasChanges, loadCoordinator.isInitialSnapshotReady)) {
                        onLocalSourceEmission(LibraryLoadSource.GOG)
                    }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            epicGameDao.observeInstalledGames()
                .catch { error ->
                    Timber.tag("LibraryViewModel").e(error, "Epic library source failed")
                    onLocalSourceEmission(LibraryLoadSource.EPIC)
                }
                .collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Epic games")

                val hasChanges = epicGameList.size != games.size || epicGameList != games
                epicGameList = games

                if (shouldProcessLibrarySourceEmission(hasChanges, loadCoordinator.isInitialSnapshotReady)) {
                    onLocalSourceEmission(LibraryLoadSource.EPIC)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            amazonGameDao.observeInstalledGames()
                .catch { error ->
                    Timber.tag("LibraryViewModel").e(error, "Amazon library source failed")
                    onLocalSourceEmission(LibraryLoadSource.AMAZON)
                }
                .collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Amazon games")
                val hasChanges = amazonGameList.size != games.size || amazonGameList != games
                amazonGameList = games
                if (shouldProcessLibrarySourceEmission(hasChanges, loadCoordinator.isInitialSnapshotReady)) {
                    onLocalSourceEmission(LibraryLoadSource.AMAZON)
                }
            }
        }

        PluviaApp.events.on<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
    }

    override fun onCleared() {
        searchDebounceJob?.cancel()
        synchronized(filterJobLock) {
            filterJob?.cancel()
        }
        PluviaApp.events.off<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        super.onCleared()
    }

    private fun onLocalSourceEmission(source: LibraryLoadSource) {
        val initialSnapshotBecameReady = loadCoordinator.markReady(source)
        if (initialSnapshotBecameReady || _state.value.initialLoadComplete) {
            onFilterApps(
                paginationPage = paginationCurrentPage,
                commitKind = FilterCommitKind.STRUCTURAL,
            )
        }
    }

    fun consumeViewportReset(token: Long): Boolean = loadCoordinator.consumeViewportReset(token)

    fun isViewportResetPending(token: Long): Boolean = loadCoordinator.isViewportResetPending(token)

    fun onLibraryUserInteraction() {
        loadCoordinator.stopBootstrapViewportStabilization()
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSourceToggle(source: GameSource) {
        onLibraryUserInteraction()
        val current = _state.value
        when (source) {
            GameSource.STEAM -> {
                val newValue = !current.showSteamInLibrary
                PrefManager.showSteamInLibrary = newValue
                _state.update { it.copy(showSteamInLibrary = newValue) }
            }

            GameSource.CUSTOM_GAME -> {
                val newValue = !current.showCustomGamesInLibrary
                PrefManager.showCustomGamesInLibrary = newValue
                _state.update { it.copy(showCustomGamesInLibrary = newValue) }
            }
            GameSource.GOG -> {
                val newValue = !current.showGOGInLibrary
                PrefManager.showGOGInLibrary = newValue
                _state.update { it.copy(showGOGInLibrary = newValue) }
            }
            GameSource.EPIC -> {
                val newValue = !current.showEpicInLibrary
                PrefManager.showEpicInLibrary = newValue
                _state.update { it.copy(showEpicInLibrary = newValue) }
            }
            GameSource.AMAZON -> {
                val newValue = !current.showAmazonInLibrary
                PrefManager.showAmazonInLibrary = newValue
                _state.update { it.copy(showAmazonInLibrary = newValue) }
            }
        }
        onFilterApps(paginationCurrentPage, FilterCommitKind.EXPLICIT)
    }

    fun onSortOptionChanged(sortOption: SortOption) {
        onLibraryUserInteraction()
        PrefManager.librarySortOption = sortOption
        _state.update { it.copy(currentSortOption = sortOption) }
        onFilterApps(commitKind = FilterCommitKind.EXPLICIT)
    }

    fun onOptionsPanelToggle(isOpen: Boolean) {
        _state.update { it.copy(isOptionsPanelOpen = isOpen) }
    }

    fun onTabChanged(tab: LibraryTab) {
        onLibraryUserInteraction()
        _state.update { it.copy(currentTab = tab) }
        onFilterApps(0, FilterCommitKind.EXPLICIT) // Reset to first page and refresh
    }

    fun onNextTab() {
        onLibraryUserInteraction()
        _state.update { currentState ->
            val nextTab = currentState.currentTab.next()
            Timber.tag("LibraryViewModel").d("Tab next via bumper: ${currentState.currentTab} -> $nextTab")
            currentState.copy(currentTab = nextTab)
        }
        onFilterApps(0, FilterCommitKind.EXPLICIT)
    }

    fun onPreviousTab() {
        onLibraryUserInteraction()
        _state.update { currentState ->
            val previousTab = currentState.currentTab.previous()
            Timber.tag("LibraryViewModel").d("Tab previous via bumper: ${currentState.currentTab} -> $previousTab")
            currentState.copy(currentTab = previousTab)
        }
        onFilterApps(0, FilterCommitKind.EXPLICIT)
    }

    fun onSearchQuery(value: String) {
        onLibraryUserInteraction()
        // Update UI immediately for responsive typing
        _state.update { it.copy(searchQuery = value) }

        // Cancel previous debounce job
        searchDebounceJob?.cancel()

        // Start new debounce job
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Only trigger filter after user stops typing
            onFilterApps(commitKind = FilterCommitKind.EXPLICIT)
        }
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        if (value == AppFilter.INSTALLED || value == AppFilter.EXPIRED) return
        onLibraryUserInteraction()
        _state.update { currentState ->
            val updatedFilter = EnumSet.copyOf(currentState.appInfoSortType)

            if (updatedFilter.contains(value)) {
                updatedFilter.remove(value)
            } else {
                updatedFilter.add(value)
            }

            PrefManager.libraryFilter = updatedFilter

            currentState.copy(appInfoSortType = updatedFilter)
        }

        onFilterApps(commitKind = FilterCommitKind.EXPLICIT)
    }

    fun onPageChange(pageIncrement: Int) {
        // Amount to change by
        var toPage = max(0, paginationCurrentPage + pageIncrement)
        toPage = min(toPage, lastPageInCurrentFilter)
        onFilterApps(toPage, FilterCommitKind.PAGINATION)
    }

    fun onRefresh() {
        onLibraryUserInteraction()
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }

            // Clear compatibility cache on manual refresh to get fresh data
            GameCompatibilityCache.clear()
            DeviceGameStatsCache.clear()
            GpuGameStatsCache.clear()

            try {
                onFilterApps(paginationCurrentPage, FilterCommitKind.METADATA).join()
                // Fetch compatibility for current page after refresh
                val currentPageGames = _state.value.appInfoList.map { it.name }
                if (currentPageGames.isNotEmpty()) {
                    fetchCompatibilityForPage(currentPageGames)
                }
                if (gpuName != "Unknown GPU") {
                    DeviceGameStatsCache.refreshIfStale(
                        deviceModel = HardwareUtils.getMachineName(),
                        gpuName = gpuName,
                        modernBuild = BuildConfig.MODERN_ANDROID,
                    )
                    GpuGameStatsCache.refreshIfStale(
                        gpuName = gpuName,
                        modernBuild = BuildConfig.MODERN_ANDROID,
                    )
                }
                _state.update {
                    it.copy(
                        deviceGameStats = DeviceGameStatsCache.getAll(),
                        gpuGameStats = GpuGameStatsCache.getAll(),
                    )
                }
                if (usesStats(_state.value)) {
                    onFilterApps(paginationCurrentPage, FilterCommitKind.STRUCTURAL)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("LibraryViewModel").e(error, "Failed to refresh library metadata")
            } finally {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun addCustomGameFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPath = File(path).absolutePath
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(normalizedPath)
            if (libraryItem == null) {
                Timber.tag("LibraryViewModel").w("Selected folder is not a valid custom game: $normalizedPath")
                return@launch
            }

            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
            if (!manualFolders.contains(normalizedPath)) {
                manualFolders.add(normalizedPath)
                PrefManager.customGameManualFolders = manualFolders
            }

            CustomGameScanner.invalidateCache()
            onFilterApps(paginationCurrentPage, FilterCommitKind.EXPLICIT)
        }
    }

    /** Whether the current sort or any active filter depends on per-game stats. */
    private fun usesStats(state: LibraryState): Boolean {
        val statSorts = setOf(
            SortOption.FPS_HIGH,
            SortOption.RUNS_HIGH,
            SortOption.REVIEWS_HIGH,
            SortOption.REVIEWS_GPU_HIGH,
        )
        if (state.currentSortOption in statSorts) return true
        return state.appInfoSortType.any {
            it == AppFilter.PLAYABLE || it == AppFilter.FIVE_STAR ||
                it == AppFilter.FIVE_STAR_GPU || it == AppFilter.PROVEN_GPU
        }
    }

    /**
     * Returns true if a game satisfies all active stat filters. Applied per-source (like
     * [GameCompatibilityCache]'s compatible filter) so the per-source tab counts stay accurate.
     * Games with no stats data are hidden whenever a stat filter is active.
     */
    private fun passesStatsFilters(state: LibraryState, source: GameSource, name: String): Boolean {
        val filters = state.appInfoSortType
        val playable = filters.contains(AppFilter.PLAYABLE)
        val fiveStar = filters.contains(AppFilter.FIVE_STAR)
        val fiveStarGpu = filters.contains(AppFilter.FIVE_STAR_GPU)
        val proven = filters.contains(AppFilter.PROVEN_GPU)
        if (!playable && !fiveStar && !fiveStarGpu && !proven) return true

        val stats = state.statsFor(source, name)
        if (playable && (stats?.fps ?: 0) < PLAYABLE_FPS_THRESHOLD) return false
        if (fiveStar && (stats?.reviewsDevice ?: 0) < 1) return false
        if (fiveStarGpu && (stats?.reviewsGpu ?: 0) < 1) return false
        if (proven && (stats?.runsGpu ?: 0) < PROVEN_RUNS_THRESHOLD) return false
        return true
    }

    private fun onFilterApps(
        paginationPage: Int = 0,
        commitKind: FilterCommitKind = FilterCommitKind.STRUCTURAL,
    ): Job {
        Timber.tag("LibraryViewModel").d("onFilterApps - appList.size: ${appList.size}, isFirstLoad: $isFirstLoad")
        if (!loadCoordinator.isInitialSnapshotReady) {
            return viewModelScope.launch { }
        }

        return synchronized(filterJobLock) {
            val filterRun = loadCoordinator.beginFilterRun(commitKind)
            filterJob?.cancel()
            viewModelScope.launch(Dispatchers.IO) {
            if (!loadCoordinator.runIfCurrent(filterRun) {
                    _state.update {
                        it.copy(
                            isLoading = shouldShowBlockingLibraryLoading(it.initialLoadComplete),
                        )
                    }
                }
            ) {
                return@launch
            }

            try {

            val currentState = _state.value
            fun passesCompatibleFilter(gameName: String): Boolean {
                if (!currentState.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                    return true
                }
                val cached = GameCompatibilityCache.getCached(gameName) ?: return true
                val status = compatibilityStatusFor(cached)
                return status == GameCompatibilityStatus.COMPATIBLE || status == GameCompatibilityStatus.GPU_COMPATIBLE
            }

            val steamFilteredBeforeCompatibility: List<SteamApp> = filterSteamAppsByType(
                apps = appList,
                filters = currentState.appInfoSortType,
            )
                .asSequence()
                .filter { item ->
                    shouldIncludeInstalledSteamApp(
                        ownerAccountIds = item.ownerAccountId,
                        currentAccountId = PrefManager.steamUserAccountId,
                        sharedOnly = currentState.appInfoSortType.contains(AppFilter.SHARED),
                    )
                }
                .filter { item ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(item.name, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .toList()

            // Filter Steam apps first (no pagination yet)
            // Note: Don't sort individual lists - we'll sort the combined list for consistent ordering
            val filteredSteamApps: List<SteamApp> = steamFilteredBeforeCompatibility
                .asSequence()
                .filter { item -> passesCompatibleFilter(item.name) }
                .filter { item -> passesStatsFilters(currentState, GameSource.STEAM, item.name) }
                .toList()

            // Map Steam apps to UI items
            data class LibraryEntry(val item: LibraryItem, val isInstalled: Boolean, val lastPlayed: Long = 0L)

            fun lastPlayedFor(appId: String): Long = playHistoryByAppId[appId] ?: 0L

            val licensedDepotMap = SteamService.buildLicensedDepotMap(filteredSteamApps)

            // Added this to avoid duplicate from custom imported steam game
            val steamEntriesAppIds = mutableSetOf<String>()

            val steamEntries: List<LibraryEntry> = filteredSteamApps.map { item ->
                val installedBranch = SteamService.getInstalledApp(item.id)?.branch ?: "public"
                // base-game size: ownedDlc=emptyMap excludes DLC depots
                val licensedDepots = licensedDepotMap[item.id]
                val resolved = SteamService.resolveDownloadableDepots(item.depots, "", emptyMap(), licensedDepots)
                val totalSizeBytes = resolved.values.sumOf { depot ->
                    depot.manifests[installedBranch]?.size ?: depot.manifests.values.firstOrNull()?.size ?: 0L
                }

                // Move appId here
                val appId = "${GameSource.STEAM.name}_${item.id}"
                steamEntriesAppIds.add(appId)

                LibraryEntry(
                    item = LibraryItem(
                        index = 0, // temporary, will be re-indexed after combining and paginating
                        appId = appId,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        capsuleImageUrl = item.getCapsuleUrl(),
                        headerImageUrl = item.headerUrl,
                        heroImageUrl = item.getHeroUrl(),
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                        sizeBytes = totalSizeBytes,
                    ),
                    isInstalled = true,
                    lastPlayed = lastPlayedFor(appId),
                )
            }

            // Scan Custom Games roots and create UI items (filtered by search query inside scanner)
            // Only include custom games if GAME filter is selected
            val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
                CustomGameScanner.scanAsLibraryItems(
                    query = currentState.searchQuery,
                )
            } else {
                emptyList()
            }
            val customEntries = customGameItems
                .filter { !steamEntriesAppIds.contains(it.appId) } // Filter out imported steam appId
                .filter { passesStatsFilters(currentState, it.gameSource, it.name) }
                .map { LibraryEntry(it, true, lastPlayed = lastPlayedFor(it.appId)) }

            // Filter GOG games
            val filteredGOGGames = gogGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .toList()

            val gogEntries = filteredGOGGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.GOG, it.title) }
                .map { game ->
                    val appId = "${GameSource.GOG.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                            capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                            headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            isShared = false,
                            gameSource = GameSource.GOG,
                        ),
                        isInstalled = true,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Filter Epic games
            val filteredEpicGames = epicGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .toList()

            val epicEntries = filteredEpicGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.EPIC, it.title) }
                .map { game ->
                    val appId = "${GameSource.EPIC.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artSquare.ifEmpty { game.artCover },
                            capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                            headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            isShared = false,
                            gameSource = GameSource.EPIC,
                        ),
                        isInstalled = true,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Amazon games
            val filteredAmazonGames = amazonGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .toList()

            val amazonEntries = filteredAmazonGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.AMAZON, it.title) }
                .map { game ->
                    val layoutHero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                        .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
                    val appId = "${GameSource.AMAZON.name}_${game.appId}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artUrl,
                            capsuleImageUrl = game.artUrl,
                            headerImageUrl = layoutHero,
                            heroImageUrl = layoutHero.ifEmpty { game.artUrl },
                            gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                            isShared = false,
                            gameSource = GameSource.AMAZON,
                        ),
                        isInstalled = true,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            coroutineContext.ensureActive()
            if (!loadCoordinator.isCurrentFilterRun(filterRun)) return@launch

            // Calculate installed counts
            val gogInstalledCount = filteredGOGGames.count { it.isInstalled }
            val epicInstalledCount = filteredEpicGames.count { it.isInstalled }
            val amazonInstalledCount = filteredAmazonGames.count { it.isInstalled }
            // Compute effective source filters based on current tab
            // ALL tab uses user preferences, other tabs override with their presets
            // Use captured currentState (not _state.value) to avoid TOCTOU race
            val currentTab = currentState.currentTab
            val includeSteam = if (currentTab == LibraryTab.ALL) {
                currentState.showSteamInLibrary
            } else {
                currentTab.showSteam
            }
            val includeOpen = if (currentTab == LibraryTab.ALL) {
                currentState.showCustomGamesInLibrary
            } else {
                currentTab.showCustom
            }

            val includeGOG = (if (currentTab == LibraryTab.ALL) {
                currentState.showGOGInLibrary
            } else {
                currentTab.showGoG
            })

            val includeEpic = (if (currentTab == LibraryTab.ALL) {
                currentState.showEpicInLibrary
            } else {
                currentTab.showEpic
            })

            val includeAmazon = (if (currentTab == LibraryTab.ALL) {
                currentState.showAmazonInLibrary
            } else {
                currentTab.showAmazon
            })

            // Combine both lists and apply sort option
            val sortComparator: Comparator<LibraryEntry> = when (currentState.currentSortOption) {
                SortOption.INSTALLED_FIRST -> compareBy<LibraryEntry> { entry ->
                    if (entry.isInstalled) 0 else 1
                }.thenBy { it.item.name.lowercase() }

                SortOption.NAME_ASC -> compareBy { it.item.name.lowercase() }

                SortOption.NAME_DESC -> compareByDescending { it.item.name.lowercase() }

                SortOption.RECENTLY_PLAYED -> LibrarySortUtils.recentlyPlayedComparator(
                    name = { it.item.name },
                    isInstalled = { it.isInstalled },
                    lastPlayed = { it.lastPlayed },
                )

                SortOption.SIZE_SMALLEST -> compareBy<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.SIZE_LARGEST -> compareByDescending<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.item.name.lowercase() }

                SortOption.FPS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.fps ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.RUNS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.runsGpu ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.REVIEWS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsDevice ?: -1
                }.thenBy { it.item.name.lowercase() }

                SortOption.REVIEWS_GPU_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsGpu ?: -1
                }.thenBy { it.item.name.lowercase() }
            }

            val combined = buildList {
                if (includeSteam) addAll(steamEntries)
                if (includeOpen) addAll(customEntries)
                if (includeGOG) addAll(gogEntries)
                if (includeEpic) addAll(epicEntries)
                if (includeAmazon) addAll(amazonEntries)
            }.sortedWith(sortComparator).mapIndexed { idx, entry ->
                entry.item.copy(index = idx, isInstalled = entry.isInstalled)
            }

            // Total count for the current filter
            val totalFound = combined.size

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            val calculatedLastPage = if (totalFound == 0) 0 else (totalFound - 1) / pageSize
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((paginationPage + 1) * pageSize, totalFound)
            val pagedList = combined.take(endIndex)

            Timber.tag("LibraryViewModel").d("Filtered list size (with Custom Games): $totalFound")

            if (isFirstLoad) {
                isFirstLoad = false
            }

            // Fetch compatibility for current page games
            if (!loadCoordinator.runIfCurrent(filterRun) {
                    fetchCompatibilityForPage(pagedList.map { it.name })
                }
            ) {
                return@launch
            }

            coroutineContext.ensureActive()
            loadCoordinator.commitIfCurrent(
                run = filterRun,
                appIdOrder = pagedList.map { it.appId },
            ) { resetToken ->
                paginationCurrentPage = paginationPage
                lastPageInCurrentFilter = calculatedLastPage
                if (currentState.searchQuery.isEmpty()) {
                    PrefManager.customGamesCount = customGameItems.size
                    PrefManager.steamGamesCount = steamFilteredBeforeCompatibility.size
                    PrefManager.gogGamesCount = filteredGOGGames.size
                    PrefManager.gogInstalledGamesCount = gogInstalledCount
                    PrefManager.epicGamesCount = filteredEpicGames.size
                    PrefManager.epicInstalledGamesCount = epicInstalledCount
                    PrefManager.amazonInstalledGamesCount = amazonInstalledCount
                    Timber.tag("LibraryViewModel").d("Saved counts - Custom: ${customGameItems.size}, Steam: ${steamFilteredBeforeCompatibility.size}, GOG: ${filteredGOGGames.size}, GOG installed: $gogInstalledCount, Epic: ${filteredEpicGames.size}, Epic installed: $epicInstalledCount, Amazon installed: $amazonInstalledCount")
                }
                _state.update {
                    it.copy(
                        appInfoList = pagedList,
                        currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                        lastPaginationPage = calculatedLastPage + 1,
                        totalAppsInFilter = displayedLibraryTotal(totalFound),
                        isLoading = false,
                        initialLoadComplete = true,
                        viewportResetToken = resetToken ?: it.viewportResetToken,
                    // Per-source counts for tab badges
                    // Use user prefs + auth state only (not current tab) so badges stay stable across tab switches
                    allCount = (if (currentState.showSteamInLibrary) steamEntries.size else 0) +
                        (if (currentState.showCustomGamesInLibrary) customEntries.size else 0) +
                        (if (currentState.showGOGInLibrary) gogEntries.size else 0) +
                        (if (currentState.showEpicInLibrary) epicEntries.size else 0) +
                        (if (currentState.showAmazonInLibrary) amazonEntries.size else 0),
                    steamCount = if (currentState.showSteamInLibrary) steamEntries.size else 0,
                    gogCount = if (currentState.showGOGInLibrary) gogEntries.size else 0,
                    epicCount = if (currentState.showEpicInLibrary) epicEntries.size else 0,
                    amazonCount = if (currentState.showAmazonInLibrary) amazonEntries.size else 0,
                    localCount = if (currentState.showCustomGamesInLibrary) customEntries.size else 0,
                    )
                }
            }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.tag("LibraryViewModel").e(error, "Filtering the library failed")
                loadCoordinator.runIfCurrent(filterRun) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            initialLoadComplete = true,
                        )
                    }
                }
                loadCoordinator.failRun(filterRun)
            } finally {
                loadCoordinator.runIfCurrent(filterRun) {
                    if (_state.value.isLoading) {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
            }
            }.also { filterJob = it }
        }
    }

    /**
     * Compares the game name against the search query using an exact match
     * and then again using a normalized form with diacritics removed.
     */
    private fun matches(gameName: String, searchQuery:String): Boolean {
        return gameName.contains(searchQuery, ignoreCase = true) || gameName.unaccent().contains(searchQuery, ignoreCase = true)
    }

    /**
     * Fetches compatibility information for games in paginated batches.
     * Checks cache first, then fetches uncached games in batches of 50.
     */
    private fun fetchCompatibilityForPage(gameNames: List<String>) {
        if (gameNames.isEmpty()) {
            Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: No game names provided")
            return
        }

        Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: Fetching compatibility for ${gameNames.size} games, GPU: $gpuName")

        // Don't make API calls if GPU name is unknown
        if (gpuName == "Unknown GPU") {
            Timber.tag("LibraryViewModel").w("Skipping compatibility fetch - GPU name is unknown")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Separate cached and uncached games
                val uncachedGames = mutableListOf<String>()
                val cachedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (gameName in gameNames) {
                    val cached = GameCompatibilityCache.getCached(gameName)
                    if (cached != null) {
                        cachedResults[gameName] = cached
                        Timber.tag("LibraryViewModel").d("Using cached result for: $gameName")
                    } else {
                        uncachedGames.add(gameName)
                    }
                }

                Timber.tag("LibraryViewModel").d("Cached: ${cachedResults.size}, Uncached: ${uncachedGames.size}")

                // Update state with cached results immediately (for instant UI update)
                if (cachedResults.isNotEmpty()) {
                    updateCompatibilityState(cachedResults)
                }

                // Only fetch if there are uncached games
                if (uncachedGames.isEmpty()) {
                    Timber.tag("LibraryViewModel").d("All games in page are cached, skipping API call")
                    return@launch
                }

                // Fetch uncached games in batches of 25
                val batchSize = 25
                val fetchedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (i in uncachedGames.indices step batchSize) {
                    val batch = uncachedGames.subList(i, min(i + batchSize, uncachedGames.size))
                    Timber.tag("LibraryViewModel").d("Fetching batch ${i / batchSize + 1} with ${batch.size} games")
                    val batchResults = GameCompatibilityService.fetchCompatibility(batch, gpuName)

                    if (batchResults != null) {
                        Timber.tag("LibraryViewModel").d("Received ${batchResults.size} results from API")
                        // Cache all results using batch caching
                        GameCompatibilityCache.cacheAll(batchResults)
                        fetchedResults.putAll(batchResults)
                    } else {
                        Timber.tag("LibraryViewModel").w("API returned null for batch")
                    }
                }

                // Update state with newly fetched results
                if (fetchedResults.isNotEmpty()) {
                    updateCompatibilityState(fetchedResults)
                    // Re-apply list filtering once new compatibility data is available
                    if (_state.value.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                        onFilterApps(paginationCurrentPage, FilterCommitKind.STRUCTURAL)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Error fetching compatibility data: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Updates the state with compatibility results.
     */
    private fun updateCompatibilityState(
        results: Map<String, GameCompatibilityService.GameCompatibilityResponse>
    ) {
        val compatibilityMap = results.mapValues { (gameName, response) ->
            compatibilityStatusFor(response)
        }

        // Update state with compatibility map (merge with existing)
        _state.update { currentState ->
            val mergedMap = currentState.compatibilityMap.toMutableMap()
            mergedMap.putAll(compatibilityMap)
            Timber.tag("LibraryViewModel").d("Updated state with ${compatibilityMap.size} compatibility entries, total: ${mergedMap.size}")
            currentState.copy(compatibilityMap = mergedMap)
        }
    }

    private fun compatibilityStatusFor(
        response: GameCompatibilityService.GameCompatibilityResponse,
    ): GameCompatibilityStatus {
        return when {
            response.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
            !response.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
            response.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
            response.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
            else -> GameCompatibilityStatus.UNKNOWN
        }
    }
}
