package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.gamenative.data.DownloadStore
import app.gamenative.data.library.LibraryFileCommitState
import app.gamenative.data.library.GameLibraryEntryKind
import app.gamenative.data.library.LibraryFileTransactionRecord

/** Persists trusted migration descriptors and atomically commits store installation locations. */
@Dao
interface LibraryFileTransactionDao {
    /** Inserts a nonce-bearing descriptor exactly once. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun prepare(record: LibraryFileTransactionRecord)

    /** Loads authoritative state for migration and startup recovery. */
    @Query("SELECT * FROM library_file_transaction WHERE transactionId = :transactionId")
    suspend fun find(transactionId: String): LibraryFileTransactionRecord?

    /** Authorizes a transaction-owned copy to occupy its final path. */
    @Query("UPDATE library_file_transaction SET targetFinalized = 1 WHERE transactionId = :transactionId")
    suspend fun markTargetFinalized(transactionId: String): Int

    /** Updates Steam's managed path without changing imported custom-path semantics. */
    @Query("UPDATE app_info SET managed_install_path = :path, custom_install_path = '' WHERE id = :appId")
    suspend fun updateSteamLocation(appId: Int, path: String): Int

    /** Updates a GOG install after its verified filesystem migration. */
    @Query("UPDATE gog_games SET install_path = :path WHERE id = :gameKey AND is_installed = 1")
    suspend fun updateGogLocation(gameKey: String, path: String): Int

    /** Updates an Epic install after its verified filesystem migration. */
    @Query("UPDATE epic_games SET install_path = :path WHERE id = :appId AND is_installed = 1")
    suspend fun updateEpicLocation(appId: Int, path: String): Int

    /** Updates an Amazon install after its verified filesystem migration. */
    @Query("UPDATE amazon_games SET install_path = :path WHERE product_id = :gameKey AND is_installed = 1")
    suspend fun updateAmazonLocation(gameKey: String, path: String): Int

    /** Moves an incomplete task to the committed destination when one exists. */
    @Query(
        "UPDATE store_download_task SET libraryId = :libraryId, libraryRoot = :libraryRoot, " +
            "installPath = :path, updatedAt = :updatedAt WHERE store = :store AND gameKey = :gameKey",
    )
    suspend fun updateTaskLocation(
        store: DownloadStore,
        gameKey: String,
        libraryId: String,
        libraryRoot: String,
        path: String,
        updatedAt: Long,
    ): Int

    /** Marks a successfully applied location update as authoritative. */
    @Query("UPDATE library_file_transaction SET commitState = 'COMMITTED' WHERE transactionId = :transactionId")
    suspend fun markCommitted(transactionId: String): Int

    /** Applies installation metadata and task location once, then durably marks the commit. */
    @Transaction
    suspend fun commit(transactionId: String) {
        val record = requireNotNull(find(transactionId)) { "Unknown migration transaction: $transactionId" }
        if (record.commitState == LibraryFileCommitState.COMMITTED.name) return
        require(record.commitState == LibraryFileCommitState.NOT_COMMITTED.name) {
            "Migration commit state is not writable: ${record.commitState}"
        }
        require(record.targetFinalized) { "Migration target was not finalized: $transactionId" }
        val entryKind = GameLibraryEntryKind.valueOf(record.entryKind)
        val store = DownloadStore.valueOf(record.store)
        if (entryKind == GameLibraryEntryKind.INSTALLED) {
            val updated = when (store) {
                DownloadStore.STEAM -> updateSteamLocation(record.appId, record.targetInstallPath)
                DownloadStore.GOG -> updateGogLocation(record.gameKey, record.targetInstallPath)
                DownloadStore.EPIC -> updateEpicLocation(record.appId, record.targetInstallPath)
                DownloadStore.AMAZON -> updateAmazonLocation(record.gameKey, record.targetInstallPath)
            }
            require(updated == 1) { "Installed game record was not updated for ${record.store}:${record.gameKey}" }
        }
        val taskUpdated = updateTaskLocation(
            store,
            record.gameKey,
            record.targetLibraryId,
            record.targetLibraryRoot,
            record.targetInstallPath,
            System.currentTimeMillis(),
        )
        if (entryKind == GameLibraryEntryKind.PARTIAL) {
            require(taskUpdated == 1) { "Partial download task was not updated for ${record.store}:${record.gameKey}" }
        }
        check(markCommitted(transactionId) == 1) { "Migration commit state was not persisted: $transactionId" }
    }

    /** Durably acknowledges completed filesystem cleanup. */
    @Query("UPDATE library_file_transaction SET cleanupComplete = 1 WHERE transactionId = :transactionId")
    suspend fun complete(transactionId: String): Int

    /** Deletes metadata after [forget] has verified durable cleanup. */
    @Query("DELETE FROM library_file_transaction WHERE transactionId = :transactionId AND cleanupComplete = 1")
    suspend fun deleteCompleted(transactionId: String): Int

    /** Idempotently forgets only a transaction whose cleanup is durably complete. */
    @Transaction
    suspend fun forget(transactionId: String) {
        val record = find(transactionId) ?: return
        require(record.cleanupComplete) { "Transaction cleanup is incomplete: $transactionId" }
        check(deleteCompleted(transactionId) == 1) { "Transaction was not forgotten: $transactionId" }
    }
}
