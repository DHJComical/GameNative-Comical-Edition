package app.gamenative.service.gog

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the shared GOG path validation policy for downloads and uninstall operations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GogLibraryPathModule {
    /** Supplies complete persisted-task validation through its interface. */
    @Binds
    abstract fun bindGogDownloadTaskPolicy(implementation: GogDownloadTaskPolicyImpl): GogDownloadTaskPolicy

    /** Supplies canonical path validation through its interface. */
    @Binds
    abstract fun bindGogLibraryPathPolicy(implementation: GogLibraryPathPolicyImpl): GogLibraryPathPolicy

    /** Supplies fail-fast GOG artifact deletion through its interface. */
    @Binds
    abstract fun bindGogUninstallFiles(implementation: GogUninstallFilesImpl): GogUninstallFiles
}
