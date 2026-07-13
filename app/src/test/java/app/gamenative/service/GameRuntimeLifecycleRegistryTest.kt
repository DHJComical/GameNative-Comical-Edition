package app.gamenative.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class GameRuntimeLifecycleRegistryTest {
    @After
    fun tearDown() = runBlocking {
        val token = GameRuntimeLifecycleRegistryImpl.requestStop() ?: return@runBlocking
        GameRuntimeLifecycleRegistryImpl.markSetupFinished(token)
        GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(token, 1_000L)
        GameRuntimeLifecycleRegistryImpl.markStopped(token)
    }

    @Test
    fun setupWindowIsActiveBeforeEnvironmentCanExist() = runBlocking {
        val token = GameRuntimeLifecycleRegistryImpl.markStarting()

        assertTrue(GameRuntimeLifecycleRegistryImpl.isActive())
        assertEquals(GameRuntimeState.STARTING, GameRuntimeLifecycleRegistryImpl.state.value)

        assertTrue(GameRuntimeLifecycleRegistryImpl.publishRunning(token) {})
        assertTrue(GameRuntimeLifecycleRegistryImpl.isActive())
        GameRuntimeLifecycleRegistryImpl.markSetupFinished(token)
        val stopping = GameRuntimeLifecycleRegistryImpl.requestStop()
        GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(token, 1_000L)
        GameRuntimeLifecycleRegistryImpl.markStopped(stopping)
        assertFalse(GameRuntimeLifecycleRegistryImpl.isActive())
    }

    @Test
    fun teardownDuringSetupStaysActiveUntilSetupJobTerminates() = runBlocking {
        val token = GameRuntimeLifecycleRegistryImpl.markStarting()
        val startedAt = System.nanoTime()
        val stopping = GameRuntimeLifecycleRegistryImpl.requestStop()

        assertTrue((System.nanoTime() - startedAt) < 50_000_000L)
        assertTrue(GameRuntimeLifecycleRegistryImpl.isActive())
        assertFalse(GameRuntimeLifecycleRegistryImpl.publishRunning(token) {})
        GameRuntimeLifecycleRegistryImpl.markSetupFinished(token)
        GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(token, 1_000L)
        assertTrue(GameRuntimeLifecycleRegistryImpl.isActive())

        GameRuntimeLifecycleRegistryImpl.markStopped(stopping)
        assertFalse(GameRuntimeLifecycleRegistryImpl.isActive())
    }

    @Test
    fun timeoutKeepsStoppingThenLaterSetupCompletionClosesSession() = runBlocking {
        val token = GameRuntimeLifecycleRegistryImpl.markStarting()
        val stopping = GameRuntimeLifecycleRegistryImpl.requestStop()

        assertFalse(GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(token, 1L))
        assertTrue(GameRuntimeLifecycleRegistryImpl.isActive())
        assertEquals(GameRuntimeState.STOPPING, GameRuntimeLifecycleRegistryImpl.state.value)

        GameRuntimeLifecycleRegistryImpl.markSetupFinished(token)
        assertTrue(GameRuntimeLifecycleRegistryImpl.awaitSetupFinished(token, 1_000L))
        GameRuntimeLifecycleRegistryImpl.markStopped(stopping)
        assertFalse(GameRuntimeLifecycleRegistryImpl.isActive())
    }
}
