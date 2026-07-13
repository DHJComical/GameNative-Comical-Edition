package app.gamenative

import app.gamenative.data.library.InstalledCatalogIdentity
import app.gamenative.data.library.InstalledCatalogIdentitySignature
import app.gamenative.data.library.InstalledCatalogIdentitySource
import app.gamenative.data.library.InstalledLibrarySynchronizationResult
import app.gamenative.data.library.InstalledLibrarySynchronizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledCatalogRescanTest {
    @Test
    fun `catalog identity upsert after empty baseline triggers rescan without install-state loops`() = runTest {
        val signatures = MutableSharedFlow<InstalledCatalogIdentitySignature>(replay = 1)
        val empty = InstalledCatalogIdentitySignature(emptyList(), emptyList(), emptyList(), emptyList())
        signatures.emit(empty)
        val source = mockk<InstalledCatalogIdentitySource>()
        every { source.observeIdentitySignatures() } returns signatures
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        coEvery { synchronizer.synchronizeAll() } returns InstalledLibrarySynchronizationResult(emptyList())
        val observer = startInstalledLibrarySynchronization(backgroundScope, source, synchronizer)

        coVerify(exactly = 1) { synchronizer.synchronizeAll() }
        signatures.emit(empty)
        advanceUntilIdle()
        coVerify(exactly = 1) { synchronizer.synchronizeAll() }

        signatures.emit(
            empty.copy(steam = listOf(InstalledCatalogIdentity("10", "game-directory"))),
        )
        advanceUntilIdle()
        coVerify(exactly = 2) { synchronizer.synchronizeAll() }
        observer.cancel()
    }

    @Test
    fun `observer failure before baseline waits for recovered baseline before startup scan`() = runTest {
        val failure = IllegalStateException("identity query failed")
        val empty = InstalledCatalogIdentitySignature(emptyList(), emptyList(), emptyList(), emptyList())
        val recovered = MutableSharedFlow<InstalledCatalogIdentitySignature>(replay = 1)
        val source = mockk<InstalledCatalogIdentitySource>()
        every { source.observeIdentitySignatures() } returnsMany listOf(flow { throw failure }, recovered)
        val synchronizer = synchronizer()
        val logs = mutableListOf<Pair<String, Throwable?>>()
        val tree = recordingTree(logs)
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        Timber.plant(tree)

        val startup = async {
            startInstalledLibrarySynchronization(
                backgroundScope,
                source,
                synchronizer,
                retryDelay = {
                    retryStarted.complete(Unit)
                    allowRetry.await()
                },
            )
        }
        retryStarted.await()
        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
        recovered.emit(empty)
        allowRetry.complete(Unit)
        val observer = startup.await()
        Timber.uproot(tree)

        assertFalse(observer.isCancelled)
        coVerify(exactly = 1) { synchronizer.synchronizeAll() }
        assertSame(failure, logs.single { it.first.contains("identity observer failed") }.second)
        observer.cancel()
    }

    @Test
    fun `observer failure after baseline and identity change is consumed without repeating startup scan`() = runTest {
        val failure = IllegalArgumentException("identity mapper failed")
        val empty = InstalledCatalogIdentitySignature(emptyList(), emptyList(), emptyList(), emptyList())
        val changed = empty.copy(epic = listOf(InstalledCatalogIdentity("catalog", "app")))
        val recovered = MutableSharedFlow<InstalledCatalogIdentitySignature>(replay = 1)
        recovered.emit(changed)
        val failingFlow = flow {
            emit(empty)
            emit(changed)
            throw failure
        }
        val source = mockk<InstalledCatalogIdentitySource>()
        every { source.observeIdentitySignatures() } returnsMany listOf(failingFlow, recovered)
        val synchronizer = synchronizer()
        val logs = mutableListOf<Pair<String, Throwable?>>()
        val tree = recordingTree(logs)
        Timber.plant(tree)

        val observer = try {
            startInstalledLibrarySynchronization(
                backgroundScope,
                source,
                synchronizer,
                retryDelay = {},
            )
        } finally {
            Timber.uproot(tree)
        }

        assertFalse(observer.isCancelled)
        coVerify(exactly = 2) { synchronizer.synchronizeAll() }
        recovered.emit(changed.copy(amazon = listOf(InstalledCatalogIdentity("product", "directory"))))
        runCurrent()
        coVerify(exactly = 3) { synchronizer.synchronizeAll() }
        assertEquals(1, logs.count { it.first.contains("identity observer failed") })
        assertSame(failure, logs.single { it.first.contains("identity observer failed") }.second)
        observer.cancel()
    }

    @Test
    fun `observer completion before baseline retries before startup scan and resumes identity changes`() = runTest {
        val empty = InstalledCatalogIdentitySignature(emptyList(), emptyList(), emptyList(), emptyList())
        val recovered = MutableSharedFlow<InstalledCatalogIdentitySignature>(replay = 1)
        val source = mockk<InstalledCatalogIdentitySource>()
        every { source.observeIdentitySignatures() } returnsMany listOf(emptyFlow(), recovered)
        val synchronizer = synchronizer()
        val delays = mutableListOf<Long>()
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()

        val startup = async {
            startInstalledLibrarySynchronization(
                backgroundScope,
                source,
                synchronizer,
                initialRetryDelayMillis = 5,
                maxRetryDelayMillis = 20,
                retryDelay = {
                    delays += it
                    retryStarted.complete(Unit)
                    allowRetry.await()
                },
            )
        }
        retryStarted.await()

        assertEquals(listOf(5L), delays)
        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
        recovered.emit(empty)
        allowRetry.complete(Unit)
        val observer = startup.await()
        coVerify(exactly = 1) { synchronizer.synchronizeAll() }
        recovered.emit(empty.copy(gog = listOf(InstalledCatalogIdentity("game", "directory"))))
        runCurrent()
        coVerify(exactly = 2) { synchronizer.synchronizeAll() }
        observer.cancel()
    }

    @Test
    fun `observer cancellation before baseline propagates to startup caller`() = runTest {
        val cancellation = CancellationException("observer cancelled")
        val source = sourceWithFlow { throw cancellation }
        val synchronizer = synchronizer()

        val thrown = try {
            startInstalledLibrarySynchronization(backgroundScope, source, synchronizer)
            null
        } catch (exception: CancellationException) {
            exception
        }

        assertSame(cancellation, thrown)
        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
    }

    @Test
    fun `cancellation during retry delay releases baseline caller without scanning`() = runTest {
        val delayEntered = CompletableDeferred<Unit>()
        var delayCancellation: CancellationException? = null
        val source = sourceWithFlow { throw IllegalStateException("initial query failed") }
        val synchronizer = synchronizer()
        val observerJob = Job()
        val observerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + observerJob)
        val startup = async {
            try {
                startInstalledLibrarySynchronization(
                    observerScope,
                    source,
                    synchronizer,
                    retryDelay = {
                        delayEntered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (exception: CancellationException) {
                            delayCancellation = exception
                            throw exception
                        }
                    },
                )
                null
            } catch (exception: CancellationException) {
                exception
            }
        }
        runCurrent()
        delayEntered.await()
        coVerify(exactly = 0) { synchronizer.synchronizeAll() }

        observerJob.cancel(CancellationException("observer scope cancelled"))
        runCurrent()

        assertSame(delayCancellation, startup.await())
        coVerify(exactly = 0) { synchronizer.synchronizeAll() }
    }

    @Test
    fun `startup synchronization cancellation cancels observer without leaking collection`() = runTest {
        val cancellation = CancellationException("startup cancelled")
        val empty = InstalledCatalogIdentitySignature(emptyList(), emptyList(), emptyList(), emptyList())
        val observerCompletion = CompletableDeferred<Throwable?>()
        val source = mockk<InstalledCatalogIdentitySource>()
        every { source.observeIdentitySignatures() } returns flow {
            emit(empty)
            awaitCancellation()
        }.onCompletion { cause -> observerCompletion.complete(cause) }
        val synchronizer = mockk<InstalledLibrarySynchronizer>()
        coEvery { synchronizer.synchronizeAll() } throws cancellation

        val thrown = try {
            startInstalledLibrarySynchronization(backgroundScope, source, synchronizer)
            null
        } catch (exception: CancellationException) {
            exception
        }

        assertSame(cancellation, thrown)
        assertNotNull(observerCompletion.await())
    }

    private fun sourceWithFlow(
        block: suspend FlowCollector<InstalledCatalogIdentitySignature>.() -> Unit,
    ): InstalledCatalogIdentitySource = mockk<InstalledCatalogIdentitySource>().also { source ->
        every { source.observeIdentitySignatures() } returns flow(block)
    }

    private fun synchronizer(): InstalledLibrarySynchronizer = mockk<InstalledLibrarySynchronizer>().also { value ->
        coEvery { value.synchronizeAll() } returns InstalledLibrarySynchronizationResult(emptyList())
    }

    private fun recordingTree(logs: MutableList<Pair<String, Throwable?>>): Timber.Tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
            logs += message to throwable
        }
    }
}
