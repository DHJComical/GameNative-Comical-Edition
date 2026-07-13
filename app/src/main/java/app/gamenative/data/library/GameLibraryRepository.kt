package app.gamenative.data.library

import app.gamenative.data.GameSource

/** Owns atomic library registration, defaults, path validation, and legacy migration. */
interface GameLibraryRepository {
    /** Loads the current state, creating built-in libraries and migrating old Steam settings once. */
    suspend fun getSnapshot(): GameLibrarySnapshot

    /** Registers a writable custom root and makes it the source's default library. */
    suspend fun addLibrary(source: GameSource, rootPath: String): GameLibrarySnapshot

    /** Selects a conflict-free registered library as the default for its store. */
    suspend fun setDefaultLibrary(source: GameSource, libraryId: String): GameLibrarySnapshot

    /** Removes a custom registration; built-in libraries cannot be removed. */
    suspend fun removeLibrary(libraryId: String): GameLibrarySnapshot

    /**
     * Resolves a conflict-free install target by id and rechecks custom-path access immediately
     * before use. Legacy conflict roots remain readable through [getSnapshot] for discovery only.
     */
    suspend fun resolveInstallation(source: GameSource, libraryId: String): GameLibraryInstallation
}
