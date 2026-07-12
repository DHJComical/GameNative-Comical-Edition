package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V23_to_V24
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreDownloadTaskMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration23To24PreservesSteamTaskAndRemovesLegacyTable() {
        helper.createDatabase(TEST_DATABASE, 23).apply {
            execSQL(
                "INSERT INTO downloading_app_info (appId, dlcAppIds, branch) VALUES (?, ?, ?)",
                arrayOf<Any>(42, "[100,200]", "beta"),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            24,
            true,
            ROOM_MIGRATION_V23_to_V24,
        )

        database.query("SELECT * FROM store_download_task WHERE store = 'STEAM' AND gameKey = '42'").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(42, cursor.getInt(cursor.getColumnIndexOrThrow("appId")))
            assertEquals("[100,200]", cursor.getString(cursor.getColumnIndexOrThrow("dlcAppIds")))
            assertEquals("beta", cursor.getString(cursor.getColumnIndexOrThrow("branch")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("installPath")))
            assertEquals("PAUSED", cursor.getString(cursor.getColumnIndexOrThrow("state")))
        }
        database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'downloading_app_info'").use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "store-download-task-migration-test"
    }
}
