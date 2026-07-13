package app.gamenative.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class CatalogGenerationTrackerTest {
    @Test
    fun endingSessionRejectsItsQueuedWork() {
        val tracker = CatalogGenerationTracker()
        val generation = tracker.begin()

        tracker.end(generation)

        assertFalse(tracker.accepts(generation))
    }

    @Test
    fun newSessionRejectsPreviousSessionWork() {
        val tracker = CatalogGenerationTracker()
        val previous = tracker.begin()
        val current = tracker.begin()

        assertFalse(tracker.accepts(previous))
        assertTrue(tracker.accepts(current))
    }

    @Test
    fun backgroundChangeWorkDoesNotRequireCatalogDemand() {
        val tracker = CatalogGenerationTracker()

        assertTrue(tracker.accepts(null))
    }

    @Test
    fun sealedSessionWaitsForRootAndChildWork() = runTest {
        val tracker = CatalogGenerationTracker()
        val generation = tracker.begin()
        assertTrue(tracker.register(generation))
        tracker.seal(generation)

        val completion = async { tracker.await(generation) }
        assertFalse(completion.isCompleted)

        assertTrue(tracker.register(generation))
        tracker.complete(generation)
        assertFalse(completion.isCompleted)

        tracker.complete(generation)
        completion.await()
        assertTrue(completion.isCompleted)
    }

    @Test
    fun endingOldSessionCannotCancelNewSession() = runTest {
        val tracker = CatalogGenerationTracker()
        val old = tracker.begin()
        val current = tracker.begin()

        tracker.end(old)
        tracker.seal(current)
        tracker.await(current)

        assertTrue(tracker.accepts(current))
    }

    @Test
    fun concurrentBeginsLeaveHighestGenerationActive() {
        val tracker = CatalogGenerationTracker()
        val generations = Collections.synchronizedList(mutableListOf<Long>())
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val executor = Executors.newFixedThreadPool(8)
        repeat(8) {
            executor.execute {
                start.await()
                generations += tracker.begin()
                done.countDown()
            }
        }

        start.countDown()
        done.await()
        executor.shutdownNow()

        val latest = generations.maxOrNull() ?: error("No generation was created")
        assertTrue(tracker.accepts(latest))
        generations.filterNot { it == latest }.forEach { assertFalse(tracker.accepts(it)) }
    }
}
