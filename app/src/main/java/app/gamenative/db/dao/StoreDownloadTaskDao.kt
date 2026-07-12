package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.gamenative.data.DownloadStore
import app.gamenative.data.StoreDownloadState
import app.gamenative.data.StoreDownloadTask

/** Provides durable download task operations shared by every downloadable store. */
@Dao
interface StoreDownloadTaskDao {
    @Upsert
    /** Creates or replaces the complete durable state of one task. */
    suspend fun upsert(task: StoreDownloadTask)

    @Query("SELECT * FROM store_download_task WHERE store = :store AND gameKey = :gameKey")
    /** Finds a task by its store-scoped game identity. */
    suspend fun find(store: DownloadStore, gameKey: String): StoreDownloadTask?

    @Query("SELECT * FROM store_download_task ORDER BY createdAt")
    /** Returns all durable tasks in creation order. */
    suspend fun getAll(): List<StoreDownloadTask>

    @Query("SELECT * FROM store_download_task WHERE store = :store AND libraryRoot = :libraryRoot ORDER BY createdAt")
    /** Returns tasks whose files belong to the specified store library. */
    suspend fun getByLibrary(store: DownloadStore, libraryRoot: String): List<StoreDownloadTask>

    @Query(
        "UPDATE store_download_task SET state = :state, updatedAt = :updatedAt " +
            "WHERE store = :store AND gameKey = :gameKey",
    )
    /** Updates a task state while preserving its selected library and download parameters. */
    suspend fun updateState(
        store: DownloadStore,
        gameKey: String,
        state: StoreDownloadState,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE store_download_task SET libraryId = :libraryId, libraryRoot = :libraryRoot, " +
            "installPath = :installPath, updatedAt = :updatedAt " +
            "WHERE store = :store AND gameKey = :gameKey",
    )
    /** Changes the durable library location after a validated migration. */
    suspend fun updateLocation(
        store: DownloadStore,
        gameKey: String,
        libraryId: String,
        libraryRoot: String,
        installPath: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE store_download_task SET state = 'PAUSED', updatedAt = :updatedAt " +
        "WHERE store = :store AND state IN ('PREPARING', 'RUNNING')",
    )
    /** Converts tasks interrupted by process death into explicitly resumable paused tasks. */
    suspend fun markInterruptedAsPaused(store: DownloadStore, updatedAt: Long): Int

    @Query("DELETE FROM store_download_task WHERE store = :store AND gameKey = :gameKey")
    /** Deletes one completed or explicitly discarded task. */
    suspend fun delete(store: DownloadStore, gameKey: String): Int
}
