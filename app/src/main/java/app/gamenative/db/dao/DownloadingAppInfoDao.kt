package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import app.gamenative.data.DownloadingAppInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Compatibility access for Steam callers that have not migrated to [StoreDownloadTaskDao]. */
@Dao
interface DownloadingAppInfoDao {
    @Transaction
    /** Creates a legacy Steam task or updates only the legacy-owned DLC and branch fields. */
    suspend fun insert(appInfo: DownloadingAppInfo) {
        val dlcAppIds = Json.encodeToString(appInfo.dlcAppIds)
        if (updateSteam(appInfo.appId, dlcAppIds, appInfo.branch) == 0) {
            insertSteam(appInfo.appId, dlcAppIds, appInfo.branch)
        }
    }

    @Query(
        "INSERT OR IGNORE INTO store_download_task " +
            "(store, gameKey, appId, libraryId, libraryRoot, installPath, dlcAppIds, branch, language, " +
            "operation, state, createdAt, updatedAt) VALUES " +
            "('STEAM', CAST(:appId AS TEXT), :appId, '', '', '', :dlcAppIds, :branch, '', " +
            "'INSTALL', 'PAUSED', 0, 0)",
    )
    /** Inserts a new legacy task after [insert] confirms no complete task exists. */
    suspend fun insertSteam(appId: Int, dlcAppIds: String, branch: String)

    @Query(
        "UPDATE store_download_task SET dlcAppIds = :dlcAppIds, branch = :branch " +
            "WHERE store = 'STEAM' AND gameKey = CAST(:appId AS TEXT)",
    )
    /** Updates only the fields represented by the legacy Steam model. */
    suspend fun updateSteam(appId: Int, dlcAppIds: String, branch: String): Int

    @Query("SELECT appId, dlcAppIds, branch FROM store_download_task WHERE store = 'STEAM'")
    /** Returns all incomplete Steam tasks in the legacy projection. */
    suspend fun getAll(): List<DownloadingAppInfo>

    @Query("SELECT appId, dlcAppIds, branch FROM store_download_task WHERE store = 'STEAM' AND appId = :appId")
    /** Returns one Steam task in the legacy projection. */
    suspend fun getDownloadingApp(appId: Int): DownloadingAppInfo?

    @Query("DELETE FROM store_download_task WHERE store = 'STEAM' AND appId = :appId")
    /** Deletes one incomplete Steam task. */
    suspend fun deleteApp(appId: Int)

    @Query("DELETE FROM store_download_task WHERE store = 'STEAM'")
    /** Deletes every incomplete Steam task without affecting other stores. */
    suspend fun deleteAll()
}
