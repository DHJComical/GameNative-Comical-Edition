package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V24_to_V25
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigration25Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration24To25AddsManagedSteamPathAndTrustedTransactionTable() {
        helper.createDatabase(TEST_DATABASE, 24).apply {
            execSQL(
                "INSERT INTO app_info (id, is_downloaded, downloaded_depots, dlc_depots) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(42, 1, "[]", "[]"),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            25,
            true,
            ROOM_MIGRATION_V24_to_V25,
        )

        database.query("SELECT managed_install_path FROM app_info WHERE id = 42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
        }
        database.query("PRAGMA table_info(library_file_transaction)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(columns.containsAll(setOf("ownershipNonce", "targetFinalized", "commitState", "cleanupComplete")))
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "library-migration-25-test"
    }
}
