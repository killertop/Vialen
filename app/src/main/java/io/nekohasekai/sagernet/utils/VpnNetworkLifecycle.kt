package io.nekohasekai.sagernet.utils

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One start/stop generation. Observes VPN availability; it does not create or own a TUN.
 * Same-Network handover is intentionally unsupported. The owner closes every TUN FD
 * before closeAndConfirm and disposes the observer after processing that result.
 */
internal class VpnNetworkLifecycle(
    private val connectivity: ConnectivityManager,
    private val matchesLinkProperties: (LinkProperties) -> Boolean,
    private val sdk: Int = Build.VERSION.SDK_INT,
    private val ownUid: Int = Process.myUid()
) {
    private class Candidate {
        var available = false
        var capabilitiesMatch = false
        var linkPropertiesMatch = false
    }

    private val lock = Any()
    private val oldNetworks = mutableSetOf<Network>()
    private val candidates = linkedMapOf<Network, Candidate>()
    private val observed = mutableSetOf<Network>()
    private val lost = mutableSetOf<Network>()
    private var begun = false
    private var registered = false
    private var established = false
    private var closing = false
    private var disposed = false
    private var readinessFailure: Throwable? = null
    private var changed = CompletableDeferred<Unit>()

    private fun signalLocked() {
        val previous = changed
        changed = CompletableDeferred()
        previous.complete(Unit)
    }

    private fun candidateLocked(network: Network): Candidate? {
        if (!begun || disposed || network in oldNetworks || network in lost) return null
        return candidates.getOrPut(network) { Candidate() }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(lock) {
            val candidate = candidateLocked(network) ?: return@synchronized
            candidate.available = true
            observed.add(network)
            signalLocked()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = synchronized(lock) {
            val candidate = candidateLocked(network) ?: return@synchronized
            candidate.capabilitiesMatch = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (sdk < Build.VERSION_CODES.R || capabilities.ownerUid == ownUid)
            signalLocked()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = synchronized(lock) {
            val candidate = candidateLocked(network) ?: return@synchronized
            try {
                candidate.linkPropertiesMatch = matchesLinkProperties(properties)
            } catch (failure: Throwable) {
                // Surface matcher failures to the startup waiter; retain the observer so
                // cleanup can still confirm LOST after the owner closes its descriptors.
                readinessFailure = failure
            }
            signalLocked()
        }

        override fun onLost(network: Network) = synchronized(lock) {
            if (!begun || disposed || network in oldNetworks) return@synchronized
            lost.add(network)
            candidates.remove(network)
            signalLocked()
        }
    }

    fun begin() = synchronized(lock) {
        check(!begun && !disposed) { "VPN observer can only begin once" }
        begun = true
        try {
            // Snapshot outside callbacks: callback arguments are the sole metadata source
            // after registration, so snapshots cannot splice different network instants.
            for (network in connectivity.allNetworks) {
                if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                    oldNetworks.add(network)
                }
            }
            val request = NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build()
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (failure: Throwable) {
            readinessFailure = failure
            disposed = true // Late callbacks from a failed registration cannot mutate state.
            signalLocked()
            throw failure
        }
    }

    fun markEstablished() = synchronized(lock) {
        check(begun && registered && !disposed) { "VPN observer is not registered" }
        // Preserve the fact that establish succeeded even if its caller raced with close.
        established = true
        signalLocked()
        check(!closing) { "VPN establish completed after closing began" }
    }

    suspend fun awaitReady(timeoutMs: Long = 5_000): Network {
        require(timeoutMs > 0)
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val signal = synchronized(lock) {
                    readinessFailure?.let { throw it }
                    check(begun && registered && !disposed) { "VPN observer is not registered" }
                    check(!closing) { "VPN is closing" }
                    val ready = if (established) candidates.entries.firstOrNull { (_, value) ->
                        value.available && (sdk < Build.VERSION_CODES.O ||
                            (value.capabilitiesMatch && value.linkPropertiesMatch))
                    }?.key else null
                    if (ready != null) return@withTimeoutOrNull ready
                    changed
                }
                signal.await()
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable readiness loop")
        } ?: throw IllegalStateException("VPN network readiness timed out")
    }

    suspend fun closeAndConfirm(timeoutMs: Long = 5_000): Boolean {
        require(timeoutMs > 0)
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            closing = true
            signalLocked()
            if (!established) return true
        }
        return withTimeoutOrNull(timeoutMs) { awaitRemoval(); true } ?: false
    }

    /** Continues the same generation's strict removal proof after the initial wait expires. */
    suspend fun awaitRemoval() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val signal = synchronized(lock) {
                check(begun && registered && !disposed) { "VPN observer is not registered" }
                check(closing) { "VPN is not closing" }
                if (!established) return
                // Empty observation is not proof, even after establish. Disposal is not LOST.
                if (observed.isNotEmpty() && observed.all { it in lost }) return
                changed
            }
            signal.await()
        }
    }

    fun dispose() {
        val unregister = synchronized(lock) {
            if (disposed) return
            disposed = true
            val result = registered
            registered = false
            signalLocked()
            result
        }
        // Mark disposed before unregister so racing/queued callbacks become inert.
        // Do not swallow registration ownership errors or retry an already attempted unregister.
        if (unregister) connectivity.unregisterNetworkCallback(callback)
    }
}
