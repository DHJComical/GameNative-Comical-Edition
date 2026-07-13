package app.gamenative.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Trusted metadata used to recover a game moved into a library-local deletion trash. */
@Entity(tableName = "library_deletion_transaction")
data class LibraryDeletionRecord(
    @PrimaryKey val transactionId: String,
    val store: String,
    val gameKey: String,
    val appId: Int,
    val entryKind: String,
    val sourcePath: String,
    val sourceExisted: Boolean,
    val trashPath: String,
    val libraryId: String,
    val state: String = "PREPARED",
)
