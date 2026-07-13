package app.gamenative.ui.screen.library

import app.gamenative.ui.model.LibraryLoadCoordinator
import app.gamenative.ui.model.FilterCommitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewportResetTest {

    @Test
    fun zeroToken_doesNotRunAnyResetAction() {
        var consumptionAttempted = false
        var actionCount = 0

        val reset = resetLibraryViewport(
            token = 0L,
            isViewportResetPending = { false },
            resetFocusTargets = { actionCount++ },
            requestGridAtTop = { actionCount++ },
            requestCarouselAtTop = { actionCount++ },
            consumeViewportReset = {
                consumptionAttempted = true
                true
            },
        )

        assertFalse(reset)
        assertEquals(0, actionCount)
        assertFalse(consumptionAttempted)
    }

    @Test
    fun publishedToken_resetsFocusAndBothLayoutsBeforeConsumption() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var token = 0L
        val events = mutableListOf<String>()
        coordinator.commitIfCurrent(run) { publishedToken ->
            token = requireNotNull(publishedToken)
        }

        assertTrue(
            resetLibraryViewport(
                token = token,
                isViewportResetPending = coordinator::isViewportResetPending,
                resetFocusTargets = { events += "focus" },
                requestGridAtTop = { events += "grid" },
                requestCarouselAtTop = { events += "carousel" },
                consumeViewportReset = {
                    events += "consume"
                    coordinator.consumeViewportReset(it)
                },
            ),
        )
        assertEquals(listOf("focus", "grid", "carousel", "consume"), events)
    }

    @Test
    fun failedRequest_doesNotConsumeTokenAndCanRetry() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var token = 0L
        coordinator.commitIfCurrent(run) { token = requireNotNull(it) }
        var consumptionAttempted = false
        var failure: Throwable? = null

        try {
            resetLibraryViewport(
                token = token,
                isViewportResetPending = coordinator::isViewportResetPending,
                resetFocusTargets = {},
                requestGridAtTop = {},
                requestCarouselAtTop = { throw IllegalStateException("layout unavailable") },
                consumeViewportReset = {
                    consumptionAttempted = true
                    coordinator.consumeViewportReset(it)
                },
            )
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertTrue(failure is IllegalStateException)
        assertFalse(consumptionAttempted)
        assertTrue(
            resetLibraryViewport(
                token = token,
                isViewportResetPending = coordinator::isViewportResetPending,
                resetFocusTargets = {},
                requestGridAtTop = {},
                requestCarouselAtTop = {},
                consumeViewportReset = coordinator::consumeViewportReset,
            ),
        )
    }

    @Test
    fun consumedTokenAfterUiRecreation_doesNotResetAgain() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var token = 0L
        coordinator.commitIfCurrent(run) { token = requireNotNull(it) }
        assertTrue(coordinator.consumeViewportReset(token))
        var scrollCount = 0

        val reset = resetLibraryViewport(
            token = token,
            isViewportResetPending = coordinator::isViewportResetPending,
            resetFocusTargets = { scrollCount++ },
            requestGridAtTop = { scrollCount++ },
            requestCarouselAtTop = { scrollCount++ },
            consumeViewportReset = coordinator::consumeViewportReset,
        )

        assertFalse(reset)
        assertEquals(0, scrollCount)
    }
}
