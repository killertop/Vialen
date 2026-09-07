package io.nekohasekai.sagernet

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.nekohasekai.sagernet.utils.VpnNetworkLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VpnNetworkLifecycleTest {
    private lateinit var connectivity: ConnectivityManager
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private val requests = mutableListOf<NetworkRequest>()
    private val owned = mutableListOf<VpnNetworkLifecycle>()
    private val uid = 12345

    @Before fun setup() {
        connectivity = mockk()
        every { connectivity.allNetworks } returns emptyArray()
        every { connectivity.getNetworkCapabilities(any()) } returns null
        every { connectivity.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } answers {
            requests.add(firstArg()); callbacks.add(secondArg()); Unit
        }
        every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
    }

    @After fun cleanup() {
        var failure: Throwable? = null
        owned.forEach { observer ->
            try { observer.dispose() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun lifecycle(sdk: Int = 34, matcher: (LinkProperties) -> Boolean = { it.interfaceName == "tun-ready" }) =
        VpnNetworkLifecycle(connectivity, matcher, sdk, uid).also { owned.add(it) }
    private fun network() = mockk<Network>()
    private fun caps(vpn: Boolean = true, owner: Int = uid) = mockk<NetworkCapabilities>().also {
        every { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns vpn
        every { it.ownerUid } returns owner
    }
    private fun links(matches: Boolean = true) = mockk<LinkProperties>().also {
        every { it.interfaceName } returns if (matches) "tun-ready" else "other"
    }
    private fun ready(callback: ConnectivityManager.NetworkCallback, network: Network) {
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, caps())
        callback.onLinkPropertiesChanged(network, links())
    }
    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (error: Throwable) { return error }
        throw AssertionError("Expected an exception")
    }
    private fun assertOriginal(expected: Throwable, actual: Throwable) {
        // Coroutine stacktrace recovery may copy standard exception types, but only a
        // same-class/message wrapper with the exact original cause is accepted.
        assertTrue(actual === expected || (actual.javaClass == expected.javaClass &&
            actual.message == expected.message && actual.cause === expected))
    }

    @Test fun snapshotsOldVpnIdsAndUsesPassiveVpnOnlyRequest() = runBlocking {
        val old = network(); val current = network()
        every { connectivity.allNetworks } returns arrayOf(old)
        every { connectivity.getNetworkCapabilities(old) } returns caps()
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val request = requests.single()
        assertTrue(request.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.awaitReady() }
        ready(callbacks.single(), old)
        assertFalse("Old VPN must not become this generation", waiting.isCompleted)
        ready(callbacks.single(), current)
        assertSame(current, waiting.await())
        verify(exactly = 1) { connectivity.getNetworkCapabilities(old) }
        verify(exactly = 0) { connectivity.getNetworkCapabilities(current) }
    }

    @Test fun api21To25RequiresOnlyFreshAvailableButStillWaitsForEstablish() = runBlocking {
        for (sdk in listOf(21, 23, 25)) {
            val observer = lifecycle(sdk); observer.begin()
            val callback = callbacks.last(); val current = network()
            val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.awaitReady() }
            callback.onAvailable(current)
            assertFalse("AVAILABLE cannot precede the establish fact", waiting.isCompleted)
            observer.markEstablished()
            assertSame(current, waiting.await())
            verify(exactly = 0) { connectivity.getNetworkCapabilities(current) }
        }
    }

    @Test fun oreoRequiresAllThreeEventsForEveryArrivalOrder() = runBlocking {
        val orders = listOf(listOf(0,1,2), listOf(0,2,1), listOf(1,0,2), listOf(1,2,0), listOf(2,0,1), listOf(2,1,0))
        for (order in orders) {
            val observer = lifecycle(26); observer.begin(); observer.markEstablished()
            val callback = callbacks.last(); val current = network()
            val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.awaitReady() }
            order.forEachIndexed { index, event ->
                when (event) {
                    0 -> callback.onAvailable(current)
                    1 -> callback.onCapabilitiesChanged(current, caps())
                    2 -> callback.onLinkPropertiesChanged(current, links())
                }
                if (index < 2) assertFalse("Incomplete events: $order", waiting.isCompleted)
            }
            assertSame(current, waiting.await())
        }
    }

    @Test fun metadataCannotBeSplicedAcrossNetworksAndMustMatchLatestValues() = runBlocking {
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val callback = callbacks.single(); val a = network(); val b = network()
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.awaitReady() }
        callback.onAvailable(a)
        callback.onCapabilitiesChanged(b, caps())
        callback.onLinkPropertiesChanged(a, links())
        assertFalse(waiting.isCompleted)
        callback.onCapabilitiesChanged(a, caps(vpn = false))
        assertFalse(waiting.isCompleted)
        callback.onLinkPropertiesChanged(a, links(matches = false))
        callback.onCapabilitiesChanged(a, caps())
        assertFalse("Wrong latest link properties must remain rejected", waiting.isCompleted)
        callback.onLinkPropertiesChanged(a, links())
        assertSame(a, waiting.await())
    }

    @Test fun androidROwnerMustMatchAndQDoesNotReadUnavailableOwnerApi() = runBlocking {
        val observer = lifecycle(30); observer.begin(); observer.markEstablished()
        val callback = callbacks.last(); val current = network()
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.awaitReady() }
        callback.onAvailable(current); callback.onLinkPropertiesChanged(current, links())
        callback.onCapabilitiesChanged(current, caps(owner = uid + 1))
        assertFalse(waiting.isCompleted)
        callback.onCapabilitiesChanged(current, caps())
        assertSame(current, waiting.await())

        val q = lifecycle(29); q.begin(); q.markEstablished()
        val qNetwork = network(); val qCallback = callbacks.last()
        val qCaps = caps()
        every { qCaps.ownerUid } throws AssertionError("Owner UID unavailable before R")
        qCallback.onAvailable(qNetwork); qCallback.onLinkPropertiesChanged(qNetwork, links())
        qCallback.onCapabilitiesChanged(qNetwork, qCaps)
        assertSame(qNetwork, q.awaitReady())
        verify(exactly = 0) { qCaps.ownerUid }
    }

    @Test fun lostNetworkCannotBeRevivedByLateMetadataOrAvailableAndAlreadyLostConfirmsStop() = runBlocking {
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val callback = callbacks.single(); val current = network()
        ready(callback, current); assertSame(current, observer.awaitReady())
        callback.onLost(current)
        ready(callback, current)
        val timedOut = failure { observer.awaitReady(20) }
        assertTrue(timedOut is IllegalStateException)
        assertFalse("Readiness timeout is a startup failure, not caller cancellation", timedOut is CancellationException)
        assertTrue(observer.closeAndConfirm(20))
    }

    @Test fun closeBeforeAvailableMustWaitForNewAvailableThenLostAndNeverReturnsReady() = runBlocking {
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val callback = callbacks.single(); val current = network()
        val closing = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.closeAndConfirm() }
        assertFalse("No observed network is not a stop acknowledgement", closing.isCompleted)
        ready(callback, current)
        assertFalse("AVAILABLE alone does not confirm stop", closing.isCompleted)
        assertTrue(failure { observer.awaitReady(20) } is IllegalStateException)
        callback.onLost(current)
        assertTrue(closing.await())
    }

    @Test fun stopRequiresEveryObservedCandidateToBeLost() = runBlocking {
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val callback = callbacks.single(); val a = network(); val b = network()
        callback.onAvailable(a); callback.onAvailable(b)
        callback.onLost(a)
        val closing = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { observer.closeAndConfirm() }
        assertFalse(closing.isCompleted)
        callback.onLost(b)
        assertTrue(closing.await())
    }

    @Test fun noEstablishConfirmsImmediatelyButEstablishedWithoutEventsTimesOutFalse() = runBlocking {
        val unopened = lifecycle(); unopened.begin()
        assertTrue(unopened.closeAndConfirm(20))
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        assertFalse(observer.closeAndConfirm(20))
        // A timeout does not dispose or falsely close the observer: later evidence still counts.
        val callback = callbacks.last(); val current = network()
        callback.onAvailable(current); callback.onLost(current)
        assertTrue(observer.closeAndConfirm(20))
    }

    @Test fun outerCancellationPropagatesFromBothWaitersAndIsNotConvertedToFalse() = runBlocking {
        for (close in listOf(false, true)) {
            val observer = lifecycle(); observer.begin(); observer.markEstablished()
            val caught = CompletableDeferred<Throwable>()
            val outer = CancellationException("outer cancellation")
            val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (close) observer.closeAndConfirm() else observer.awaitReady()
                    throw AssertionError("Cancelled wait returned normally")
                } catch (error: CancellationException) { caught.complete(error); throw error }
            }
            assertFalse(waiting.isCompleted)
            waiting.cancel(outer); waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
            assertOriginal(outer, caught.await())
        }
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val outerDeadline = failure { withTimeout(20) { observer.closeAndConfirm(5_000) } }
        assertTrue("Outer timeout must not be swallowed by inner withTimeoutOrNull", outerDeadline is CancellationException)
    }

    @Test fun disposeIsIdempotentAndOldCallbackCannotChangeNewGeneration() = runBlocking {
        val old = lifecycle(); old.begin(); old.markEstablished()
        val oldCallback = callbacks.last(); old.dispose(); old.dispose()
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(oldCallback) }
        val current = lifecycle(); current.begin(); current.markEstablished()
        val currentCallback = callbacks.last(); val network = network()
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { current.awaitReady() }
        ready(oldCallback, network); oldCallback.onLost(network)
        assertFalse("Disposed callback must not complete the new instance", waiting.isCompleted)
        ready(currentCallback, network)
        assertSame(network, waiting.await())
    }

    @Test fun failedRegistrationIsObservableAndNeverUnregistersUnownedCallback() = runBlocking {
        val denied = SecurityException("registration denied")
        every { connectivity.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>()) } answers {
            callbacks.add(secondArg())
            throw denied
        }
        val observer = lifecycle()
        assertOriginal(denied, failure { observer.begin() })
        ready(callbacks.single(), network()) // queued callback remains inert after failed registration.
        assertOriginal(denied, failure { observer.awaitReady(20) })
        assertTrue(observer.closeAndConfirm(20))
        observer.dispose(); observer.dispose()
        verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun unregisterFailureIsObservableAndNotRetriedByDispose() = runBlocking {
        val observer = lifecycle(); observer.begin()
        val denied = IllegalStateException("unregister failed")
        every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws denied
        assertOriginal(denied, failure { observer.dispose() })
        observer.dispose()
        verify(exactly = 1) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun matcherFailureReachesStartupWhileLostCanStillConfirmCleanup() = runBlocking {
        val denied = IllegalStateException("link matcher failed")
        val observer = lifecycle(matcher = { throw denied }); observer.begin(); observer.markEstablished()
        val callback = callbacks.single(); val current = network()
        callback.onAvailable(current); callback.onCapabilitiesChanged(current, caps())
        callback.onLinkPropertiesChanged(current, links())
        assertOriginal(denied, failure { observer.awaitReady(20) })
        callback.onLost(current)
        assertTrue(observer.closeAndConfirm(20))
    }
    @Test fun lateRemovalContinuesOriginalOracleAfterInitialDeadline() = runBlocking {
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val callback = callbacks.last(); val a = network(); val b = network()
        ready(callback, a); ready(callback, b)
        assertFalse(observer.closeAndConfirm(20))
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            observer.awaitRemoval()
        }
        assertFalse(waiting.isCompleted)
        callback.onLost(a)
        assertFalse(waiting.isCompleted) // Every observed network must be lost.
        callback.onLost(b)
        withTimeout(1_000) { waiting.await() }
        verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test fun disposedObserverFailsIndefiniteRemovalWaitEvenIfLateLostArrives() = runBlocking {
        val observer = lifecycle(); observer.begin(); observer.markEstablished()
        val callback = callbacks.last(); val current = network()
        ready(callback, current)
        assertFalse(observer.closeAndConfirm(20))
        // Capture the failure inside the child, so it cannot cancel this test's parent.
        val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            failure { observer.awaitRemoval() }
        }
        observer.dispose()
        callback.onLost(current)
        assertTrue(withTimeout(1_000) { waiting.await() } is IllegalStateException)
    }

}
