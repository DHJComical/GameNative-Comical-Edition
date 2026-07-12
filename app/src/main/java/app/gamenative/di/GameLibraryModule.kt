package app.gamenative.di

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.data.library.GameLibraryRepository
import app.gamenative.data.library.GameLibraryRepositoryImpl
import app.gamenative.data.library.GameLibraryEntryStore
import app.gamenative.data.library.GameLibraryOperations
import app.gamenative.data.library.GameLibraryOperationsImpl
import app.gamenative.data.library.LibraryFileTransaction
import app.gamenative.data.library.LibraryFileTransactionImpl
import app.gamenative.data.library.PathAccessPolicyImpl
import app.gamenative.data.library.GameLibraryEntryStoreImpl
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
