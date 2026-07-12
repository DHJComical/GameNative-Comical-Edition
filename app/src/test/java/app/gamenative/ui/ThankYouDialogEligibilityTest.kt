package app.gamenative.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThankYouDialogEligibilityTest {
    @Test
    fun enabledSettingShowsDialogInStandardBuild() {
        assertTrue(shouldShowThankYouDialog(showThankYouDialog = true, isGoldBuild = false))
    }

    @Test
    fun disabledSettingHidesDialogInStandardBuild() {
        assertFalse(shouldShowThankYouDialog(showThankYouDialog = false, isGoldBuild = false))
    }

    @Test
    fun goldBuildHidesDialogRegardlessOfSetting() {
        assertFalse(shouldShowThankYouDialog(showThankYouDialog = true, isGoldBuild = true))
        assertFalse(shouldShowThankYouDialog(showThankYouDialog = false, isGoldBuild = true))
    }

    @Test
    fun changingStoredSettingUpdatesEligibilityImmediately() {
        val isGoldBuild = false

        assertTrue(shouldShowThankYouDialog(showThankYouDialog = true, isGoldBuild = isGoldBuild))
        assertFalse(shouldShowThankYouDialog(showThankYouDialog = false, isGoldBuild = isGoldBuild))
        assertTrue(shouldShowThankYouDialog(showThankYouDialog = true, isGoldBuild = isGoldBuild))
    }

}
