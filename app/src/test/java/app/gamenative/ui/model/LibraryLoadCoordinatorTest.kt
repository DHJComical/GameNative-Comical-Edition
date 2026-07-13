package app.gamenative.ui.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLoadCoordinatorTest {

    @Test
    fun blockingLoadingIsShownBeforeInitialLibraryCommit() {
        assertTrue(shouldShowBlockingLibraryLoading(initialLoadComplete = false))
    }

    @Test
    fun blockingLoadingIsHiddenAfterInitialLibraryCommit() {
        assertFalse(shouldShowBlockingLibraryLoading(initialLoadComplete = true))
    }

    @Test
    fun unchangedEmissionIsProcessedUntilInitialSnapshotIsReady() {
        assertTrue(shouldProcessLibrarySourceEmission(hasChanges = false, initialSnapshotReady = false))
    }

    @Test
    fun unchangedEmissionIsIgnoredAfterInitialSnapshotIsReady() {
        assertFalse(shouldProcessLibrarySourceEmission(hasChanges = false, initialSnapshotReady = true))
        assertTrue(shouldProcessLibrarySourceEmission(hasChanges = true, initialSnapshotReady = true))
    }

    @Test
    fun initialSnapshot_isReleasedOnlyAfterEveryLocalSourceEmits() {
        val coordinator = LibraryLoadCoordinator()

        LibraryLoadSource.entries.dropLast(1).forEach { source ->
            assertFalse(coordinator.markReady(source))
            assertFalse(coordinator.isInitialSnapshotReady)
        }

        assertTrue(coordinator.markReady(LibraryLoadSource.entries.last()))
        assertTrue(coordinator.isInitialSnapshotReady)
    }

    @Test
    fun initialSnapshot_isReleasedOnlyOnce() {
        val coordinator = LibraryLoadCoordinator()
        LibraryLoadSource.entries.forEach(coordinator::markReady)

        assertFalse(coordinator.markReady(LibraryLoadSource.STEAM))
    }

    @Test
    fun newerFilterRun_invalidatesOlderRun() {
        val coordinator = LibraryLoadCoordinator()

        val olderRun = coordinator.beginFilterRun()
        val newerRun = coordinator.beginFilterRun()

        assertFalse(coordinator.isCurrentFilterRun(olderRun))
        assertTrue(coordinator.isCurrentFilterRun(newerRun))
    }

    @Test
    fun obsoleteRun_cannotCommitAfterNewerRunBegins() {
        val coordinator = LibraryLoadCoordinator()
        val olderRun = coordinator.beginFilterRun()
        val newerRun = coordinator.beginFilterRun()
        var committedRun: Long? = null

        assertFalse(coordinator.commitIfCurrent(olderRun) { committedRun = olderRun })
        assertTrue(coordinator.commitIfCurrent(newerRun) { committedRun = newerRun })
        assertEquals(newerRun, committedRun)
    }

    @Test
    fun obsoleteRun_cannotExecuteCommitSideEffects() {
        val coordinator = LibraryLoadCoordinator()
        val olderRun = coordinator.beginFilterRun()
        coordinator.beginFilterRun()
        var sideEffectCount = 0

        assertFalse(coordinator.commitIfCurrent(olderRun) { sideEffectCount++ })
        assertEquals(0, sideEffectCount)
    }

    @Test
    fun commitAndNewGeneration_areSerializedAcrossThreads() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun()
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val commitCompleted = AtomicBoolean(false)

        val commitThread = Thread {
            coordinator.commitIfCurrent(run) {
                commitEntered.countDown()
                releaseCommit.await()
                commitCompleted.set(true)
            }
        }
        commitThread.start()
        val nextRunThread = Thread { coordinator.beginFilterRun() }
        try {
            assertTrue(commitEntered.await(1, TimeUnit.SECONDS))
            nextRunThread.start()
        } finally {
            releaseCommit.countDown()
        }
        commitThread.join(1_000)
        nextRunThread.join(1_000)

        assertTrue(commitCompleted.get())
        assertFalse(commitThread.isAlive)
        assertFalse(nextRunThread.isAlive)
        assertFalse(coordinator.isCurrentFilterRun(run))
    }

    @Test
    fun viewportReset_survivesObsoleteRunAndIsConsumedOnce() {
        val coordinator = LibraryLoadCoordinator()
        val resetRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        val replacementRun = coordinator.beginFilterRun()
        var resetToken: Long? = null

        assertFalse(coordinator.commitIfCurrent(resetRun) { resetToken = it })
        assertTrue(coordinator.commitIfCurrent(replacementRun) { resetToken = it })
        val publishedToken = requireNotNull(resetToken)
        assertTrue(coordinator.consumeViewportReset(publishedToken))
        assertFalse(coordinator.consumeViewportReset(publishedToken))
    }

    @Test
    fun backgroundCommit_doesNotCreateViewportReset() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun()
        var resetToken: Long? = 1L

        assertTrue(coordinator.commitIfCurrent(run) { resetToken = it })
        assertNull(resetToken)
    }

    @Test
    fun bootstrapStructuralCommits_createSuccessiveViewportResets() {
        val coordinator = LibraryLoadCoordinator()
        val firstRun = coordinator.beginFilterRun()
        var firstToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(firstRun, listOf("STEAM_1")) { firstToken = it })

        val secondRun = coordinator.beginFilterRun()
        var secondToken: Long? = null
        assertTrue(
            coordinator.commitIfCurrent(secondRun, listOf("STEAM_2", "STEAM_1")) {
                secondToken = it
            },
        )

        assertEquals(1L, firstToken)
        assertEquals(2L, secondToken)
    }

    @Test
    fun interactionStopsBootstrapResetForLaterStructuralCommit() {
        val coordinator = LibraryLoadCoordinator()
        val firstRun = coordinator.beginFilterRun()
        coordinator.commitIfCurrent(firstRun, listOf("STEAM_1")) { }
        coordinator.stopBootstrapViewportStabilization()

        val laterRun = coordinator.beginFilterRun()
        var resetToken: Long? = 1L
        assertTrue(coordinator.commitIfCurrent(laterRun, listOf("STEAM_2", "STEAM_1")) { resetToken = it })

        assertNull(resetToken)
    }

    @Test
    fun explicitResetStillPublishesAfterBootstrapStabilizationStops() {
        val coordinator = LibraryLoadCoordinator()
        val firstRun = coordinator.beginFilterRun()
        coordinator.commitIfCurrent(firstRun, listOf("STEAM_1")) { }
        coordinator.stopBootstrapViewportStabilization()

        val sortRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var resetToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(sortRun, listOf("STEAM_1")) { resetToken = it })

        assertEquals(2L, resetToken)
    }

    @Test
    fun explicitResetDoesNotReactivateBootstrapStabilization() {
        val coordinator = LibraryLoadCoordinator()
        val firstRun = coordinator.beginFilterRun()
        coordinator.commitIfCurrent(firstRun, listOf("STEAM_1")) { }
        coordinator.stopBootstrapViewportStabilization()

        val explicitRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var explicitToken: Long? = null
        coordinator.commitIfCurrent(explicitRun, listOf("STEAM_2", "STEAM_1")) {
            explicitToken = it
        }

        val backgroundRun = coordinator.beginFilterRun()
        var backgroundToken: Long? = 1L
        coordinator.commitIfCurrent(backgroundRun, listOf("STEAM_3", "STEAM_2", "STEAM_1")) {
            backgroundToken = it
        }

        assertEquals(2L, explicitToken)
        assertNull(backgroundToken)
    }

    @Test
    fun unchangedOrderDoesNotCreateAnotherBootstrapReset() {
        val coordinator = LibraryLoadCoordinator()
        val appIdOrder = listOf("STEAM_1", "GOG_2")
        val firstRun = coordinator.beginFilterRun()
        coordinator.commitIfCurrent(firstRun, appIdOrder) { }

        val metadataRun = coordinator.beginFilterRun(FilterCommitKind.METADATA)
        var resetToken: Long? = 1L
        assertTrue(coordinator.commitIfCurrent(metadataRun, appIdOrder) { resetToken = it })

        assertNull(resetToken)
    }

    @Test
    fun publishedViewportResetIsPendingUntilConsumed() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var resetToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(run) { resetToken = it })
        val token = requireNotNull(resetToken)

        assertTrue(coordinator.isViewportResetPending(token))
        assertTrue(coordinator.consumeViewportReset(token))
        assertFalse(coordinator.isViewportResetPending(token))
    }

    @Test
    fun zeroAndUnpublishedViewportResetTokensAreNotPending() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var resetToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(run) { resetToken = it })
        val token = requireNotNull(resetToken)

        assertFalse(coordinator.isViewportResetPending(0L))
        assertFalse(coordinator.isViewportResetPending(token + 1L))
    }

    @Test
    fun olderViewportResetTokenIsNotPendingAfterNewTokenIsPublished() {
        val coordinator = LibraryLoadCoordinator()
        val firstRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var firstToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(firstRun) { firstToken = it })

        val secondRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var secondToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(secondRun) { secondToken = it })

        assertFalse(coordinator.isViewportResetPending(requireNotNull(firstToken)))
        assertTrue(coordinator.isViewportResetPending(requireNotNull(secondToken)))
    }

    @Test
    fun failedCurrentRun_clearsItsPendingViewportReset() {
        val coordinator = LibraryLoadCoordinator()
        val failedRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)

        assertTrue(coordinator.failRun(failedRun))

        val nextRun = coordinator.beginFilterRun()
        var resetToken: Long? = 1L
        assertTrue(coordinator.commitIfCurrent(nextRun) { resetToken = it })
        assertNull(resetToken)
    }

    @Test
    fun failedReplacementPreservesInheritedExplicitResetForLaterCommit() {
        val coordinator = LibraryLoadCoordinator()
        coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        val replacementRun = coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)

        assertTrue(coordinator.failRun(replacementRun))

        val laterRun = coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        var resetToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(laterRun, listOf("STEAM_1")) { resetToken = it })
        assertEquals(1L, resetToken)
    }

    @Test
    fun obsoleteFailure_doesNotClearReplacementViewportReset() {
        val coordinator = LibraryLoadCoordinator()
        val failedRun = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        val replacementRun = coordinator.beginFilterRun()

        assertFalse(coordinator.failRun(failedRun))

        var resetToken: Long? = null
        assertTrue(coordinator.commitIfCurrent(replacementRun) { resetToken = it })
        assertTrue(coordinator.consumeViewportReset(requireNotNull(resetToken)))
    }

    @Test
    fun interactionCancelsPublishedBootstrapReset() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        var resetToken: Long? = null
        coordinator.commitIfCurrent(run, listOf("STEAM_1")) { resetToken = it }
        val token = requireNotNull(resetToken)

        coordinator.stopBootstrapViewportStabilization()

        assertFalse(coordinator.isViewportResetPending(token))
        assertFalse(coordinator.consumeViewportReset(token))
    }

    @Test
    fun interactionPreservesPublishedExplicitReset() {
        val coordinator = LibraryLoadCoordinator()
        val run = coordinator.beginFilterRun(FilterCommitKind.EXPLICIT)
        var resetToken: Long? = null
        coordinator.commitIfCurrent(run, listOf("STEAM_1")) { resetToken = it }
        val token = requireNotNull(resetToken)

        coordinator.stopBootstrapViewportStabilization()

        assertTrue(coordinator.isViewportResetPending(token))
        assertTrue(coordinator.consumeViewportReset(token))
    }

    @Test
    fun paginationUpdatesOrderWithoutPublishingBootstrapReset() {
        val coordinator = LibraryLoadCoordinator()
        val initialRun = coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        var initialToken: Long? = null
        coordinator.commitIfCurrent(initialRun, listOf("STEAM_1")) { initialToken = it }
        assertTrue(coordinator.consumeViewportReset(requireNotNull(initialToken)))

        val paginationRun = coordinator.beginFilterRun(FilterCommitKind.PAGINATION)
        var paginationToken: Long? = 1L
        coordinator.commitIfCurrent(paginationRun, listOf("STEAM_1", "STEAM_2")) {
            paginationToken = it
        }

        assertNull(paginationToken)

        val unchangedStructuralRun = coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        var structuralToken: Long? = 1L
        coordinator.commitIfCurrent(unchangedStructuralRun, listOf("STEAM_1", "STEAM_2")) {
            structuralToken = it
        }
        assertNull(structuralToken)
    }

    @Test
    fun paginationReplacementInheritsPendingStructuralReset() {
        val coordinator = LibraryLoadCoordinator()
        coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        val replacementRun = coordinator.beginFilterRun(FilterCommitKind.PAGINATION)
        var resetToken: Long? = null

        coordinator.commitIfCurrent(replacementRun, listOf("STEAM_2", "STEAM_1")) {
            resetToken = it
        }

        assertEquals(1L, resetToken)
    }

    @Test
    fun metadataReplacementInheritsPendingStructuralReset() {
        val coordinator = LibraryLoadCoordinator()
        coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        val replacementRun = coordinator.beginFilterRun(FilterCommitKind.METADATA)
        var resetToken: Long? = null

        coordinator.commitIfCurrent(replacementRun, listOf("GOG_2", "STEAM_1")) {
            resetToken = it
        }

        assertEquals(1L, resetToken)
    }

    @Test
    fun interactionClearsInheritedStructuralResetBeforeReplacementCommit() {
        val coordinator = LibraryLoadCoordinator()
        coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        val replacementRun = coordinator.beginFilterRun(FilterCommitKind.PAGINATION)
        coordinator.stopBootstrapViewportStabilization()
        var resetToken: Long? = 1L

        coordinator.commitIfCurrent(replacementRun, listOf("STEAM_2", "STEAM_1")) {
            resetToken = it
        }

        assertNull(resetToken)
    }

    @Test
    fun failedStructuralOwnerDoesNotResetUnrelatedFutureMetadataCommit() {
        val coordinator = LibraryLoadCoordinator()
        val structuralRun = coordinator.beginFilterRun(FilterCommitKind.STRUCTURAL)
        assertTrue(coordinator.failRun(structuralRun))
        val metadataRun = coordinator.beginFilterRun(FilterCommitKind.METADATA)
        var resetToken: Long? = 1L

        coordinator.commitIfCurrent(metadataRun, listOf("STEAM_1")) { resetToken = it }

        assertNull(resetToken)
    }
}
