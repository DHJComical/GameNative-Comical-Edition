package app.gamenative.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo

/** Store identifiers persisted independently from enum declaration order. */
enum class DownloadStore(val persistedValue: String) {
    STEAM("STEAM"),
    GOG("GOG"),
    EPIC("EPIC"),
    AMAZON("AMAZON"),
}

/** Download operations that need to survive process restarts. */
enum class StoreDownloadOperation(val persistedValue: String) {
    INSTALL("INSTALL"),
    UPDATE("UPDATE"),
    VERIFY("VERIFY"),
}

/** Durable task states; active states are normalized to PAUSED after a restart. */
enum class StoreDownloadState(val persistedValue: String) {
    PREPARING("PREPARING"),
    RUNNING("RUNNING"),
    PAUSED("PAUSED"),
    FAILED("FAILED"),
}

/**
 * Persists the exact library and install directory selected for an incomplete store download.
 * The store-specific game key is used with [store] because numeric app IDs overlap across stores.
 */
@Entity(
    tableName = "store_download_task",
    primaryKeys = ["store", "gameKey"],
    indices = [
        Index(value = ["state"]),
        Index(value = ["store", "libraryRoot"]),
    ],
)
data class StoreDownloadTask(
    val store: DownloadStore,
    val gameKey: String,
    val appId: Int,
    val libraryId: String,
    val libraryRoot: String,
    val installPath: String,
    val dlcAppIds: List<Int> = emptyList(),
    @ColumnInfo(defaultValue = "public")
    val branch: String = "public",
    val language: String = "",
    @ColumnInfo(defaultValue = "INSTALL")
    val operation: StoreDownloadOperation = StoreDownloadOperation.INSTALL,
    @ColumnInfo(defaultValue = "PAUSED")
    val state: StoreDownloadState = StoreDownloadState.PAUSED,
    val createdAt: Long,
    val updatedAt: Long,
)
