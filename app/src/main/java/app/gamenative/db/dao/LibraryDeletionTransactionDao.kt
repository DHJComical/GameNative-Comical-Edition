package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.gamenative.data.DownloadStore
import app.gamenative.data.library.GameLibraryEntryKind
import app.gamenative.data.library.LibraryDeletionRecord

/** Persists recoverable trash moves used by destructive library removal. */
@Dao
interface LibraryDeletionTransactionDao {
    /** Records the exact source and trash paths before rename. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun prepare(record: LibraryDeletionRecord)

    /** Returns every deletion requiring startup reconciliation. */
    @Query("SELECT * FROM library_deletion_transaction")
    suspend fun getAll(): List<LibraryDeletionRecord>

    /** Counts deletion cleanup that still owns files or metadata for a library. */
    @Query("SELECT COUNT(*) FROM library_deletion_transaction WHERE libraryId = :libraryId")
    suspend fun countByLibrary(libraryId: String): Int

    /** Advances a deletion after its same-filesystem rename or metadata commit. */
    @Query("UPDATE library_deletion_transaction SET state = :state WHERE transactionId = :transactionId")
    suspend fun updateState(transactionId: String, state: String): Int

    @Query("DELETE FROM app_info WHERE id = :appId")
    suspend fun deleteSteamInstall(appId: Int): Int

    @Query("DELETE FROM app_info WHERE id IN (SELECT id FROM steam_app WHERE dlc_for_app_id = :appId)")
    suspend fun deleteSteamDlcInstalls(appId: Int): Int

    @Query("DELETE FROM app_change_numbers WHERE appId = :appId")
    suspend fun deleteSteamChangeNumbers(appId: Int): Int

    @Query("DELETE FROM app_file_change_lists WHERE appId = :appId")
    suspend fun deleteSteamFileChangeLists(appId: Int): Int

    @Query("DELETE FROM steam_file_hash_cache WHERE appId = :appId")
    suspend fun deleteSteamHashCache(appId: Int): Int

    @Query("UPDATE steam_app SET workshop_mods = 0, enabled_workshop_item_ids = '', workshop_download_pending = 0 WHERE id = :appId")
    suspend fun clearSteamWorkshop(appId: Int): Int

    @Query("UPDATE gog_games SET is_installed = 0, install_path = '', install_size = 0 WHERE id = :gameKey")
    suspend fun deleteGogInstall(gameKey: String): Int

    @Query("UPDATE epic_games SET is_installed = 0, install_path = '', install_size = 0 WHERE id = :appId")
    suspend fun deleteEpicInstall(appId: Int): Int

    @Query("UPDATE amazon_games SET is_installed = 0, install_path = '', install_size = 0, version_id = '' WHERE product_id = :gameKey")
    suspend fun deleteAmazonInstall(gameKey: String): Int

    @Query("DELETE FROM store_download_task WHERE store = :store AND gameKey = :gameKey")
    suspend fun deleteTask(store: DownloadStore, gameKey: String): Int

    /** Atomically deletes authoritative metadata and enters retryable post-commit cleanup. */
    @Transaction
    suspend fun commitMetadata(transactionId: String) {
        val record = getAll().singleOrNull { it.transactionId == transactionId }
            ?: throw IllegalArgumentException("Unknown deletion transaction: $transactionId")
        if (record.state == "METADATA_COMMITTED") return
        require(record.state == "MOVED") { "Deletion files were not moved: $transactionId" }
        val kind = GameLibraryEntryKind.valueOf(record.entryKind)
        val store = DownloadStore.valueOf(record.store)
        if (kind == GameLibraryEntryKind.INSTALLED) {
            val changed = when (store) {
                DownloadStore.STEAM -> {
                    val main = deleteSteamInstall(record.appId)
                    deleteSteamDlcInstalls(record.appId)
                    deleteSteamChangeNumbers(record.appId)
                    deleteSteamFileChangeLists(record.appId)
                    deleteSteamHashCache(record.appId)
                    clearSteamWorkshop(record.appId)
                    main
                }
                DownloadStore.GOG -> deleteGogInstall(record.gameKey)
                DownloadStore.EPIC -> deleteEpicInstall(record.appId)
                DownloadStore.AMAZON -> deleteAmazonInstall(record.gameKey)
            }
            require(changed == 1) { "Installed metadata was not deleted for ${record.store}:${record.gameKey}" }
        }
        val taskDeleted = deleteTask(store, record.gameKey)
        if (kind == GameLibraryEntryKind.PARTIAL) {
            require(taskDeleted == 1) { "Partial task was not deleted for ${record.store}:${record.gameKey}" }
        }
        check(updateState(transactionId, "METADATA_COMMITTED") == 1)
    }

    /** Forgets a fully reconciled deletion. */
    @Query("DELETE FROM library_deletion_transaction WHERE transactionId = :transactionId")
    suspend fun delete(transactionId: String): Int

    /** Clears installed metadata for a detached library entry without touching files. */
    @Transaction
    suspend fun clearDetachedMetadata(store: DownloadStore, gameKey: String, appId: Int) {
        when (store) {
            DownloadStore.STEAM -> {
                deleteSteamInstall(appId)
                deleteSteamDlcInstalls(appId)
                deleteSteamChangeNumbers(appId)
                deleteSteamFileChangeLists(appId)
                deleteSteamHashCache(appId)
                clearSteamWorkshop(appId)
            }
            DownloadStore.GOG -> deleteGogInstall(gameKey)
            DownloadStore.EPIC -> deleteEpicInstall(appId)
            DownloadStore.AMAZON -> deleteAmazonInstall(gameKey)
        }
        deleteTask(store, gameKey)
    }
}
