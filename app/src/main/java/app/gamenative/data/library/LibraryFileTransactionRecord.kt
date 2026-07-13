package app.gamenative.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.gamenative.data.DownloadStore

/** Application-owned durable metadata used to authenticate and recover one library migration. */
@Entity(tableName = "library_file_transaction")
data class LibraryFileTransactionRecord(
    /** Globally unique filesystem journal identifier. */
    @PrimaryKey val transactionId: String,
    /** Persisted [DownloadStore] value. */
    val store: String,
    /** Store-specific stable game identifier. */
    val gameKey: String,
    /** Internal application identifier used for events and Steam metadata. */
    val appId: Int,
    /** Persisted [GameLibraryEntryKind] determining which metadata must exist at commit. */
    val entryKind: String,
    /** Canonical source directory retained until commit succeeds. */
    val sourcePath: String,
    /** Target path relative to the selected library root. */
    val targetRelativePath: String,
    /** Unpredictable token authenticating transaction-owned filesystem content. */
    val ownershipNonce: String,
    /** Stable target library identifier. */
    val targetLibraryId: String,
    /** Canonical target library root. */
    val targetLibraryRoot: String,
    /** Canonical final install or staging path persisted on commit. */
    val targetInstallPath: String,
    /** True after trusted metadata authorizes the finalized target. */
    val targetFinalized: Boolean = false,
    /** Persisted [LibraryFileCommitState] name. */
    val commitState: String = LibraryFileCommitState.NOT_COMMITTED.name,
    /** True after filesystem cleanup is durably acknowledged. */
    val cleanupComplete: Boolean = false,
)
