package app.gamenative.service

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/** Tracks one demand-driven catalog generation and all work derived from it. */
internal class CatalogGenerationTracker {
    private data class Session(
        val generation: Long,
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
        var pendingWork: Int = 0,
        var producerSealed: Boolean = false,
    )

    private val counter = AtomicLong(0L)
    private val lock = Any()
    private var activeSession: Session? = null

    fun begin(): Long = synchronized(lock) {
        activeSession?.completion?.cancel()
        val session = Session(counter.incrementAndGet())
        activeSession = session
        session.generation
    }

    fun end(generation: Long) {
        synchronized(lock) {
            val session = activeSession ?: return
            if (session.generation != generation) return
            activeSession = null
            session.completion.cancel()
        }
    }

    fun current(): Long? = synchronized(lock) { activeSession?.generation }

    fun accepts(generation: Long?): Boolean = generation == null || synchronized(lock) {
        activeSession?.generation == generation
    }

    /** Registers queued or child work while the generation is still current. */
    fun register(generation: Long): Boolean = synchronized(lock) {
        val session = activeSession
        if (session?.generation != generation || session.completion.isCompleted) return false
        session.pendingWork++
        true
    }

    fun complete(generation: Long) {
        synchronized(lock) {
            val session = activeSession ?: return
            if (session.generation != generation) return
            check(session.pendingWork > 0) { "Catalog work completed without registration" }
            session.pendingWork--
            completeIfReady(session)
        }
    }

    fun fail(generation: Long, error: Throwable) {
        synchronized(lock) {
            val session = activeSession ?: return
            if (session.generation != generation) return
            activeSession = null
            session.completion.completeExceptionally(error)
        }
    }

    /** Signals that the root producer has finished adding initial work. */
    fun seal(generation: Long) {
        synchronized(lock) {
            val session = activeSession ?: return
            if (session.generation != generation) return
            session.producerSealed = true
            completeIfReady(session)
        }
    }

    suspend fun await(generation: Long) {
        val completion = synchronized(lock) {
            val session = activeSession
            check(session?.generation == generation) { "Catalog generation is no longer active" }
            session.completion
        }
        completion.await()
    }

    private fun completeIfReady(session: Session) {
        if (session.producerSealed && session.pendingWork == 0 && !session.completion.isCompleted) {
            session.completion.complete(Unit)
        }
    }
}
