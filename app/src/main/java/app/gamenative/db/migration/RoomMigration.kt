package app.gamenative.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val DROP_TABLE = "DROP TABLE IF EXISTS " // Trailing Space

internal val ROOM_MIGRATION_V7_to_V8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        // Dec 5, 2025: Friends and Chat features removed
        connection.execSQL(DROP_TABLE + "chat_message")
        connection.execSQL(DROP_TABLE + "emoticon")
        connection.execSQL(DROP_TABLE + "steam_friend")
    }
}

internal val ROOM_MIGRATION_V24_to_V25 = object : Migration(24, 25) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `app_info` ADD COLUMN `managed_install_path` TEXT NOT NULL DEFAULT ''")
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_file_transaction` (
                `transactionId` TEXT NOT NULL,
                `store` TEXT NOT NULL,
                `gameKey` TEXT NOT NULL,
                `appId` INTEGER NOT NULL,
                `entryKind` TEXT NOT NULL,
                `sourcePath` TEXT NOT NULL,
                `targetRelativePath` TEXT NOT NULL,
                `ownershipNonce` TEXT NOT NULL,
                `targetLibraryId` TEXT NOT NULL,
                `targetLibraryRoot` TEXT NOT NULL,
                `targetInstallPath` TEXT NOT NULL,
                `targetFinalized` INTEGER NOT NULL,
                `commitState` TEXT NOT NULL,
                `cleanupComplete` INTEGER NOT NULL,
                PRIMARY KEY(`transactionId`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_deletion_transaction` (
                `transactionId` TEXT NOT NULL,
                `store` TEXT NOT NULL,
                `gameKey` TEXT NOT NULL,
                `appId` INTEGER NOT NULL,
                `entryKind` TEXT NOT NULL,
                `sourcePath` TEXT NOT NULL,
                `sourceExisted` INTEGER NOT NULL,
                `trashPath` TEXT NOT NULL,
                `libraryId` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                PRIMARY KEY(`transactionId`)
            )
            """.trimIndent(),
        )
    }
}

internal val ROOM_MIGRATION_V23_to_V24 = object : Migration(23, 24) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `store_download_task` (
                `store` TEXT NOT NULL,
                `gameKey` TEXT NOT NULL,
                `appId` INTEGER NOT NULL,
                `libraryId` TEXT NOT NULL,
                `libraryRoot` TEXT NOT NULL,
                `installPath` TEXT NOT NULL,
                `dlcAppIds` TEXT NOT NULL,
                `branch` TEXT NOT NULL DEFAULT 'public',
                `language` TEXT NOT NULL,
                `operation` TEXT NOT NULL DEFAULT 'INSTALL',
                `state` TEXT NOT NULL DEFAULT 'PAUSED',
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`store`, `gameKey`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            """
            INSERT INTO `store_download_task` (
                `store`, `gameKey`, `appId`, `libraryId`, `libraryRoot`, `installPath`,
                `dlcAppIds`, `branch`, `language`, `operation`, `state`, `createdAt`, `updatedAt`
            )
            SELECT 'STEAM', CAST(`appId` AS TEXT), `appId`, '', '', '',
                `dlcAppIds`, `branch`, '', 'INSTALL', 'PAUSED', 0, 0
            FROM `downloading_app_info`
            """.trimIndent(),
        )
        connection.execSQL("DROP TABLE `downloading_app_info`")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_store_download_task_state` ON `store_download_task` (`state`)")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_store_download_task_store_libraryRoot` " +
                "ON `store_download_task` (`store`, `libraryRoot`)",
        )
    }
}
