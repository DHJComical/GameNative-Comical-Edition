package app.gamenative.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generation identifying one setup job; stale generations cannot publish runtime state. */
class GameRuntimeToken internal constructor(val generation: Long)

/** Thread-safe authoritative lifecycle for the single local game runtime. */
interface GameRuntimeLifecycleRegistry {
    val state: StateFlow<GameRuntimeState>
    fun markStarting(): GameRuntimeToken
    fun publishRunning(token: GameRuntimeToken, publish: () -> Unit): Boolean
    fun markSetupFinished(token: GameRuntimeToken)
    fun requestStop(): GameRuntimeToken?
    suspend fun awaitSetupFinished(token: GameRuntimeToken, timeoutMillis: Long): Boolean
    fun markStopped(token: GameRuntimeToken?)
    fun isActive(): Boolean
}

/** Explicit local runtime states replacing environment-presence heuristics. */
enum class GameRuntimeState { STOPPED, STARTING, RUNNING, STOPPING }

/** Synchronized generation implementation that closes setup/teardown publication races. */
object GameRuntimeLifecycleRegistryImpl : GameRuntimeLifecycleRegistry {
    private data class Session(
        val token: GameRuntimeToken,
        val setupFinished: CountDownLatch = CountDownLatch(1),
        var stopRequested: Boolean = false,
    )

    private val generations = AtomicLong()
    private val mutableState = MutableStateFlow(GameRuntimeState.STOPPED)
    private var session: Session? = null
    override val state: StateFlow<GameRuntimeState> = mutableState.asStateFlow()

    @Synchronized
    override fun markStarting(): GameRuntimeToken {
        check(session == null) { "A game runtime session is already active" }
        val token = GameRuntimeToken(generations.incrementAndGet())
        session = Session(token)
        mutableState.value = GameRuntimeState.STARTING
        return token
    }

    @Synchronized
    override fun publishRunning(token: GameRuntimeToken, publish: () -> Unit): Boolean {
        val current = session
        if (current?.token != token || current.stopRequested) return false
        publish()
        mutableState.value = GameRuntimeState.RUNNING
        return true
    }

    @Synchronized
    override fun markSetupFinished(token: GameRuntimeToken) {
        session?.takeIf { it.token == token }?.setupFinished?.countDown()
    }

    @Synchronized
    override fun requestStop(): GameRuntimeToken? {
        val current = session ?: return null
        current.stopRequested = true
        mutableState.value = GameRuntimeState.STOPPING
        return current.token
    }

    override suspend fun awaitSetupFinished(token: GameRuntimeToken, timeoutMillis: Long): Boolean {
        val latch = synchronized(this) { session?.takeIf { it.token == token }?.setupFinished } ?: return true
        return withContext(Dispatchers.IO) {
            if (timeoutMillis == WAIT_FOREVER) {
                latch.await()
                true
            } else {
                latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            }
        }
    }

    @Synchronized
    override fun markStopped(token: GameRuntimeToken?) {
        val current = session ?: return
        if (token != null && current.token != token) return
        check(current.setupFinished.count == 0L) { "Cannot stop lifecycle before setup job exits" }
        session = null
        mutableState.value = GameRuntimeState.STOPPED
    }

    @Synchronized
    override fun isActive(): Boolean = session != null

    const val WAIT_FOREVER = -1L
}
