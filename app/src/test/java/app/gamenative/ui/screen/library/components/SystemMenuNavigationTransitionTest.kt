package app.gamenative.ui.screen.library.components

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SystemMenuNavigationTransitionTest(
    private val target: String,
) {

    @Test
    fun dismissesBeforeRunningTargetAndConsumesBackDuringExit() = runTest {
        val events = mutableListOf<String>()
        val transitionStates = mutableListOf(false)
        val transition = SystemMenuNavigationTransition()

        assertTrue(
            transition.start(
                scope = this,
                onDismiss = { events += "dismiss" },
                onTransitionChanged = { transitionStates += it },
                action = { events += target },
            ),
        )
        assertFalse(
            transition.start(
                scope = this,
                onDismiss = { events += "duplicate-dismiss" },
                onTransitionChanged = { transitionStates += it },
                action = { events += "duplicate-$target" },
            ),
        )

        assertEquals(listOf("dismiss"), events)
        assertEquals(listOf(false, true), transitionStates)
        assertTrue(transition.inProgress)
        assertTrue(
            systemMenuTransitionConsumesBack(
                isActive = true,
                navigationTransitionInProgress = transition.inProgress,
            ),
        )
        assertFalse(
            systemMenuTransitionConsumesBack(
                isActive = false,
                navigationTransitionInProgress = transition.inProgress,
            ),
        )

        advanceTimeBy(MENU_EXIT_DURATION_MS - 1L)
        runCurrent()

        assertEquals(listOf("dismiss"), events)
        assertTrue(transition.inProgress)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf("dismiss", target), events)
        assertEquals(listOf(false, true, false), transitionStates)
        assertFalse(transition.inProgress)
        assertFalse(
            systemMenuTransitionConsumesBack(
                isActive = true,
                navigationTransitionInProgress = transition.inProgress,
            ),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "target={0}")
        fun targets(): List<String> = listOf("settings", "downloads", "storage")
    }
}
