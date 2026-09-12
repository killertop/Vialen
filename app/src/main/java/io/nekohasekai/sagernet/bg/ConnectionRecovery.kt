package io.nekohasekai.sagernet.bg

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Service-owned, single-dispatcher controller. No Android validation or interface-name heuristic.
 * A failed endpoint is not proof of a broken tunnel: recovery is deliberately best-effort,
 * capped per service lifetime, and never restarts the service.
 */
internal class ConnectionRecovery(
    context: kotlin.coroutines.CoroutineContext,
    private val now: () -> Long,
    private val probe: suspend () -> Health,
    private val reset: suspend () -> Unit,
    private val networkEnabled: () -> Boolean,
    private val wakeEnabled: () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val report: (Event) -> Unit = {},
) {
    enum class Health { Healthy, Failed, Unavailable }
    enum class Reason { Network, Wake }
    enum class Kind { Scheduled, ProbeResult, Reset, PostResetResult, Cancel, BudgetExhausted, Cooldown }
    data class Event(val kind: Kind, val reason: Reason?, val health: Health?, val resetCount: Int)
    data class NetworkIdentity(val handle: Long, val interfaceName: String?)

    private val owner = SupervisorJob(context[Job])
    private val scope = CoroutineScope(context + owner)
    private val probeMutex = Mutex()
    private var work: Job? = null
    private var generation = 0L
    private var network: NetworkIdentity? = null
    private var networkObserved = false
    private var pendingNetworkRecovery = false
    private var ready = false
    private var screenOn = true
    private var idle = false
    private var sleepingSince: Long? = null
    private var lastProbeCompleted: Long? = null
    private var lastReset: Long? = null
    private var resets = 0

    fun connected() {
        if (!owner.isActive) return
        ready = true
        recoverPendingNetwork()
    }

    fun networkChanged(value: NetworkIdentity?) {
        if (!owner.isActive) return
        val changed = network != value
        val previouslyObserved = networkObserved
        networkObserved = true
        if (!changed) return
        network = value
        pendingNetworkRecovery = pendingNetworkRecovery || ready || previouslyObserved
        invalidate()
        recoverPendingNetwork()
    }

    private fun recoverPendingNetwork(): Boolean {
        if (!ready || network == null || idle || !pendingNetworkRecovery ||
            !networkEnabled()) return false
        pendingNetworkRecovery = false
        schedule(Reason.Network)
        return true
    }

    fun screenChanged(on: Boolean) {
        if (!owner.isActive || screenOn == on) return
        screenOn = on
        if (!on) {
            sleepingSince = sleepingSince ?: now()
        } else if (!idle) {
            checkWake()
        }
    }

    fun idleChanged(value: Boolean) {
        if (!owner.isActive || idle == value) return
        idle = value
        if (value) {
            sleepingSince = sleepingSince ?: now()
            if (work?.isActive == true && activeReason == Reason.Network) pendingNetworkRecovery = true
            invalidate()
        } else if (!recoverPendingNetwork() && screenOn) {
            checkWake()
        }
    }

    private fun checkWake() {
        if (recoverPendingNetwork()) return
        val since = sleepingSince ?: return
        sleepingSince = null
        // An in-flight network recovery already covers this wake; do not cancel its worker.
        if (work?.isActive == true && activeReason == Reason.Network) return
        val checkedSince = maxOf(since, lastProbeCompleted ?: since)
        if (ready && network != null && now() - checkedSince >= MIN_SLEEP_MS && wakeEnabled()) {
            schedule(Reason.Wake)
        }
    }

    private var activeReason: Reason? = null
    private fun emit(kind: Kind, health: Health? = null) {
        report(Event(kind, activeReason, health, resets))
    }

    private fun invalidate() {
        generation++
        if (work?.isActive == true) emit(Kind.Cancel)
        work?.cancel()
        work = null
    }

    private fun schedule(reason: Reason) {
        invalidate()
        activeReason = reason
        emit(Kind.Scheduled)
        val ticket = generation
        work = scope.launch {
            pause(SETTLE_MS)
            // Cancellation waits for the native probe worker to exit before a new probe can run.
            probeMutex.withLock {
                fun current() = owner.isActive && ticket == generation && ready &&
                    network != null && !idle &&
                    (if (reason == Reason.Wake) wakeEnabled() else networkEnabled())
                suspend fun checkHealth(kind: Kind): Health {
                    val result = probe()
                    currentCoroutineContext().ensureActive()
                    if (current()) {
                        if (result != Health.Unavailable) lastProbeCompleted = now()
                        emit(kind, result)
                    }
                    return result
                }
                if (!current()) return@withLock
                if (checkHealth(Kind.ProbeResult) != Health.Failed) return@withLock
                pause(RETRY_MS)
                if (!current() || checkHealth(Kind.ProbeResult) != Health.Failed) return@withLock
                if (!current()) return@withLock
                if (resets >= MAX_RESETS) { emit(Kind.BudgetExhausted); return@withLock }
                if (lastReset?.let { now() - it < RESET_COOLDOWN_MS } == true) {
                    emit(Kind.Cooldown); return@withLock
                }
                // Consume the budget even if the native reset throws. Never retry reset in a loop.
                resets++
                lastReset = now()
                emit(Kind.Reset)
                try {
                    reset()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@withLock
                }
                pause(SETTLE_MS)
                if (current()) checkHealth(Kind.PostResetResult) // No reset loop on failure.
            }
        }
    }

    fun stop() { ready = false; invalidate(); owner.cancel() }
    suspend fun join() { owner.join() }

    companion object {
        const val SETTLE_MS = 1_500L
        const val RETRY_MS = 2_000L
        const val MIN_SLEEP_MS = 30_000L
        const val RESET_COOLDOWN_MS = 60_000L
        const val MAX_RESETS = 3
        const val PROBE_TIMEOUT_MS = 3_000

        /** Use only the existing user-selected test endpoint. Never log it or native errors.
         * Reject embedded credentials/query tokens; do not rewrite DNS, scheme or routes.
         */
        fun probeTarget(value: String): String? = try {
            val uri = URI(value)
            value.takeIf {
                uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
            }
        } catch (_: Exception) { null }
    }
}
