package app.gamenative.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.db.migration.ROOM_MIGRATION_V25_to_V26
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModMigration26Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PluviaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration25To26AddsCompleteModSchema() {
        helper.createDatabase(TEST_DATABASE, 25).close()

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            26,
            true,
            ROOM_MIGRATION_V25_to_V26,
        )

        assertEquals(MOD_TABLES, database.schemaObjects("table", "mod_%"))
        assertEquals(MOD_INDICES, database.schemaObjects("index", "index_mod_%"))
        assertEquals(
            setOf(
                ForeignKey("mod_install", "CASCADE"),
                ForeignKey("mod_profile", "CASCADE"),
            ),
            database.foreignKeys("mod_profile_install_state"),
        )
        assertEquals(
            setOf(ForeignKey("mod_install", "CASCADE")),
            database.foreignKeys("mod_placement_recipe"),
        )
        assertEquals(
            setOf(ForeignKey("mod_install", "CASCADE")),
            database.foreignKeys("mod_overwrite_manifest"),
        )
        database.close()
    }

    private fun SupportSQLiteDatabase.schemaObjects(type: String, namePattern: String): Set<String> =
        query(
            "SELECT name FROM sqlite_master WHERE type = ? AND name LIKE ?",
            arrayOf(type, namePattern),
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SupportSQLiteDatabase.foreignKeys(tableName: String): Set<ForeignKey> =
        query("PRAGMA foreign_key_list(`$tableName`)").use { cursor ->
            val tableColumn = cursor.getColumnIndexOrThrow("table")
            val onDeleteColumn = cursor.getColumnIndexOrThrow("on_delete")
            buildSet {
                while (cursor.moveToNext()) {
                    add(ForeignKey(cursor.getString(tableColumn), cursor.getString(onDeleteColumn)))
                }
            }
        }

    private data class ForeignKey(val table: String, val onDelete: String)

    private companion object {
        const val TEST_DATABASE = "mod-migration-26-test"

        val MOD_TABLES = setOf(
            "mod_install",
            "mod_profile",
            "mod_profile_install_state",
            "mod_placement_recipe",
            "mod_overwrite_manifest",
        )

        val MOD_INDICES = setOf(
            "index_mod_install_app_id",
            "index_mod_install_app_id_source_nexus_game_domain_nexus_mod_id_nexus_file_id",
            "index_mod_profile_app_id",
            "index_mod_profile_app_id_name",
            "index_mod_profile_install_state_app_id",
            "index_mod_profile_install_state_install_id",
            "index_mod_profile_install_state_app_id_profile_id_priority",
            "index_mod_placement_recipe_install_id",
            "index_mod_overwrite_manifest_install_id",
            "index_mod_overwrite_manifest_target_path",
        )
    }
}
