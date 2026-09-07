package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V26_to_V27
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedModSourcesMigration27Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration26To27AddsCompatibilityTableAndReshapesModInstall() {
        helper.createDatabase(TEST_DATABASE, 26).apply {
            execSQL(
                "INSERT INTO mod_install (install_id, app_id, source, nexus_game_domain, nexus_mod_id, " +
                    "nexus_file_id, mod_name, file_name, version, size_bytes, archive_path, extracted_path, " +
                    "enabled, status, created_at, updated_at, downloaded_at, metadata_json) VALUES " +
                    "('install-1', '42', 'NEXUS', 'skyrim', 1, 2, 'Mod', 'mod.zip', '1.0', 10, " +
                    "'/a.zip', '/e', 1, 'READY', 0, 0, 0, '{}')",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            27,
            true,
            ROOM_MIGRATION_V26_to_V27,
        )

        database.query("PRAGMA table_info(`downloading_app_info`)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(columns.containsAll(setOf("appId", "dlcAppIds", "branch")))
        }
        database.query("PRAGMA table_info(`mod_install`)").use { cursor ->
            val columns = buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            assertTrue(columns.contains("archive_sha256"))
        }
        database.query("SELECT archive_sha256, nexus_game_domain FROM mod_install WHERE install_id = 'install-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            assertEquals("skyrim", cursor.getString(1))
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "managed-mod-sources-migration-27-test"
    }
}
