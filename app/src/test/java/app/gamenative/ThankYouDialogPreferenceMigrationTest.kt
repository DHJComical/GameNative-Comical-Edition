package app.gamenative

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThankYouDialogPreferenceMigrationTest {
    private val migration = ThankYouDialogPreferenceMigration()

    @Test
    fun legacyTipStateDisablesDialogDuringFirstMigration() = runTest {
        val migrated = migration.migrate(mutablePreferencesOf(tippedPreferenceKey to true))

        assertFalse(migrated[showThankYouDialogPreferenceKey]!!)
        assertTrue(migrated[thankYouDialogPreferenceMigratedKey]!!)
    }

    @Test
    fun legacyNonTipStateKeepsDialogEnabledDuringFirstMigration() = runTest {
        val migrated = migration.migrate(mutablePreferencesOf(tippedPreferenceKey to false))

        assertTrue(migrated[showThankYouDialogPreferenceKey]!!)
        assertTrue(migrated[thankYouDialogPreferenceMigratedKey]!!)
    }

    @Test
    fun missingLegacyTipStateKeepsDialogEnabledDuringFirstMigration() = runTest {
        val migrated = migration.migrate(mutablePreferencesOf())

        assertTrue(migrated[showThankYouDialogPreferenceKey]!!)
        assertTrue(migrated[thankYouDialogPreferenceMigratedKey]!!)
    }

    @Test
    fun migrationMarkerPreventsRerun() = runTest {
        val current = mutablePreferencesOf(thankYouDialogPreferenceMigratedKey to true)

        assertFalse(migration.shouldMigrate(current))
    }

    @Test
    fun absentMigrationMarkerRequiresMigration() = runTest {
        assertTrue(migration.shouldMigrate(mutablePreferencesOf()))
    }

    @Test
    fun existingNewPreferenceIsNotOverwritten() = runTest {
        val unrelatedPreferenceKey = stringPreferencesKey("unrelated_preference")
        val current = mutablePreferencesOf(
            tippedPreferenceKey to true,
            showThankYouDialogPreferenceKey to true,
            unrelatedPreferenceKey to "preserved",
        )

        val migrated = migration.migrate(current)

        assertEquals(true, migrated[showThankYouDialogPreferenceKey])
        assertEquals("preserved", migrated[unrelatedPreferenceKey])
        assertTrue(migrated[thankYouDialogPreferenceMigratedKey]!!)
    }

    @Test
    fun supportConfirmationAtomicallyRecordsTipAndDisablesDialog() {
        val preferences = mutablePreferencesOf(
            tippedPreferenceKey to false,
            showThankYouDialogPreferenceKey to true,
        )

        applyThankYouDialogSupportConfirmation(preferences)

        assertEquals(true, preferences[tippedPreferenceKey])
        assertEquals(false, preferences[showThankYouDialogPreferenceKey])
    }
}
