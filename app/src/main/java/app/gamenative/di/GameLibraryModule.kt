package app.gamenative.di

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.data.library.AmazonInstallDiscovery
import app.gamenative.data.library.AmazonInstallDiscoveryImpl
import app.gamenative.data.library.AmazonInstallReconciler
import app.gamenative.data.library.AmazonInstallReconcilerImpl
import app.gamenative.data.library.EpicInstallDiscovery
import app.gamenative.data.library.EpicInstallDiscoveryImpl
import app.gamenative.data.library.EpicInstallReconciler
import app.gamenative.data.library.EpicInstallReconcilerImpl
import app.gamenative.data.library.GameLibraryEntryStore
import app.gamenative.data.library.GameLibraryEntryStoreImpl
import app.gamenative.data.library.GameLibraryOperations
import app.gamenative.data.library.GameLibraryOperationsImpl
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.GameLibraryRepositoryImpl
import app.gamenative.data.library.GogInstallDiscovery
import app.gamenative.data.library.GogInstallDiscoveryImpl
import app.gamenative.data.library.GogInstallReconciler
import app.gamenative.data.library.GogInstallReconcilerImpl
import app.gamenative.data.library.InstalledLibrarySynchronizer
import app.gamenative.data.library.InstalledLibrarySynchronizerImpl
import app.gamenative.data.library.InstalledCatalogIdentitySource
import app.gamenative.data.library.InstalledCatalogIdentitySourceImpl
import app.gamenative.data.library.LibraryFileTransaction
import app.gamenative.data.library.LibraryFileTransactionImpl
import app.gamenative.data.library.PathAccessPolicyImpl
import app.gamenative.data.library.SteamInstallDiscovery
import app.gamenative.data.library.SteamInstallDiscoveryImpl
import app.gamenative.data.library.SteamInstallReconciler
import app.gamenative.data.library.SteamInstallReconcilerImpl
import app.gamenative.db.PluviaDatabase
import app.gamenative.service.GameRuntimeLifecycleRegistry
import app.gamenative.service.GameRuntimeLifecycleRegistryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Supplies the single application-wide managed game-library repository. */
@Module
@InstallIn(SingletonComponent::class)
object GameLibraryModule {
    /** Observes catalog identity changes that can make another local install discoverable. */
    @Provides
    @Singleton
    fun provideInstalledCatalogIdentitySource(database: PluviaDatabase): InstalledCatalogIdentitySource =
        InstalledCatalogIdentitySourceImpl(
            database.gogGameDao(),
            database.epicGameDao(),
            database.amazonGameDao(),
        )

    /** Supplies the structured Steam filesystem scanner. */
    @Provides
    @Singleton
    fun provideSteamInstallDiscovery(): SteamInstallDiscovery = SteamInstallDiscoveryImpl()

    /** Supplies positive-only Steam catalog and installation reconciliation. */
    @Provides
    @Singleton
    fun provideSteamInstallReconciler(
        database: PluviaDatabase,
        discovery: SteamInstallDiscovery,
    ): SteamInstallReconciler = SteamInstallReconcilerImpl(database, discovery)

    /** Supplies the structured GOG filesystem scanner. */
    @Provides
    @Singleton
    fun provideGogInstallDiscovery(): GogInstallDiscovery = GogInstallDiscoveryImpl()

    /** Supplies positive-only GOG installation reconciliation. */
    @Provides
    @Singleton
    fun provideGogInstallReconciler(
        database: PluviaDatabase,
        discovery: GogInstallDiscovery,
    ): GogInstallReconciler = GogInstallReconcilerImpl(database.gogGameDao(), discovery)

    /** Supplies the strict Epic marker and native-manifest scanner. */
    @Provides
    @Singleton
    fun provideEpicInstallDiscovery(): EpicInstallDiscovery = EpicInstallDiscoveryImpl()

    /** Supplies positive-only Epic installation reconciliation. */
    @Provides
    @Singleton
    fun provideEpicInstallReconciler(database: PluviaDatabase): EpicInstallReconciler =
        EpicInstallReconcilerImpl(database.epicGameDao())

    /** Supplies the strict marker-only Amazon scanner. */
    @Provides
    @Singleton
    fun provideAmazonInstallDiscovery(): AmazonInstallDiscovery = AmazonInstallDiscoveryImpl()

    /** Supplies positive-only Amazon installation reconciliation. */
    @Provides
    @Singleton
    fun provideAmazonInstallReconciler(database: PluviaDatabase): AmazonInstallReconciler =
        AmazonInstallReconcilerImpl(database.amazonGameDao())

    /** Coordinates serialized installed-game scans across every managed store. */
    @Provides
    @Singleton
    fun provideInstalledLibrarySynchronizer(
        repository: GameLibraryRepository,
        steamReconciler: SteamInstallReconciler,
        gogReconciler: GogInstallReconciler,
        epicDiscovery: EpicInstallDiscovery,
        epicReconciler: EpicInstallReconciler,
        amazonDiscovery: AmazonInstallDiscovery,
        amazonReconciler: AmazonInstallReconciler,
        database: PluviaDatabase,
    ): InstalledLibrarySynchronizer = InstalledLibrarySynchronizerImpl(
        repository,
        steamReconciler,
        gogReconciler,
        epicDiscovery,
        epicReconciler,
        amazonDiscovery,
        amazonReconciler,
        database.epicGameDao(),
        database.amazonGameDao(),
    )

    /** Shares the authoritative local game lifecycle with storage operations. */
    @Provides
    @Singleton
    fun provideGameRuntimeLifecycleRegistry(): GameRuntimeLifecycleRegistry = GameRuntimeLifecycleRegistryImpl

    /** Supplies the recoverable filesystem transaction implementation. */
    @Provides
    @Singleton
    fun provideLibraryFileTransaction(): LibraryFileTransaction = LibraryFileTransactionImpl()

    /** Exposes the Room-backed store adapter through its business boundary. */
    @Provides
    @Singleton
    fun provideGameLibraryEntryStore(implementation: GameLibraryEntryStoreImpl): GameLibraryEntryStore = implementation

    /** Exposes library operations without coupling callers to the implementation. */
    @Provides
    @Singleton
    fun provideGameLibraryOperations(implementation: GameLibraryOperationsImpl): GameLibraryOperations = implementation

    /**
     * Creates store-native built-in roots and checks broad filesystem access again at each
     * custom-library write boundary.
     */
    @Provides
    @Singleton
    fun provideGameLibraryRepository(
        @ApplicationContext context: Context,
    ): GameLibraryRepository = GameLibraryRepositoryImpl(
        builtInRoots = mapOf(
            GameSource.STEAM to File(context.dataDir, "Steam").path,
            GameSource.GOG to File(context.dataDir, "GOG").path,
            GameSource.EPIC to File(context.dataDir, "Epic").path,
            GameSource.AMAZON to File(context.dataDir, "Amazon").path,
        ),
        pathAccessPolicy = PathAccessPolicyImpl(context),
    )
}
