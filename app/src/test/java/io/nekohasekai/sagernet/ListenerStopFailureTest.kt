package io.nekohasekai.sagernet

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Handler
import android.os.PowerManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ListenerStopFailureTest {
    private lateinit var connectivity: ConnectivityManager
    private val keys = mutableListOf<Any>()
    private val warnings = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private var listenerMocked = false

    private class FakeService : BaseService.Interface {
        // killProcesses reads only proxy. Avoid the unrelated UtilsKt JVM shadow
        // constructor path (broadcastReceiver is not present in that shadow).
        override val data = mockk<BaseService.Data>().also { state ->
            every { state.proxy } returns null
            every { state.proxy = null } just Runs
            every { state.recovery } returns null
            every { state.recovery = null } just Runs
        }
        override val tag = "ListenerStopFailureFake"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null
        override fun createNotification(profileName: String): ServiceNotification =
            error("Listener cleanup must not create notifications")
        override fun acquireWakeLock() = Unit
    }

    @Before
    fun setup() {
        connectivity = mockk()
        mockkObject(SagerNet.Companion)
        every { SagerNet.connectivity } returns connectivity
        mockkObject(Logs)
        every { Logs.w(any<String>()) } just Runs
        every { Logs.w(any<Throwable>()) } answers { warnings.add(firstArg()); Unit }
        every { Logs.w(any<String>(), any<Throwable>()) } answers { warnings.add(secondArg()); Unit }
        allowRegistration()
        every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
    }

    private fun allowRegistration() {
        every {
            connectivity.registerBestMatchingNetworkCallback(
                any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>(), any<Handler>()
            )
        } just Runs
    }

    private fun key(): Any = Any().also { keys.add(it) }
    private fun service(): FakeService = FakeService().also { keys.add(it) }

    private fun originalOrRecoveryCause(expected: Throwable, actual: Throwable?): Throwable? {
        if (actual === expected) return actual
        // Coroutine stacktrace recovery can copy a standard exception. Accept only
        // a single same-class/message wrapper whose immediate cause IS the original.
        // A new exception with merely matching type/text still fails assertSame.
        return if (actual != null && actual.javaClass == expected.javaClass &&
            actual.message == expected.message && actual.cause === expected
        ) actual.cause else actual
    }

    @After
    fun cleanup(): Unit = runBlocking {
        var cleanupFailure: Throwable? = null
        suspend fun clean(action: suspend () -> Unit) {
            try { action() } catch (error: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = error else previous.addSuppressed(error)
            }
        }
        try {
            if (listenerMocked) unmockkObject(DefaultNetworkListener)
            // Restore the platform stub before removing only this test's keys.
            allowRegistration()
            every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
            keys.asReversed().forEach { owned ->
                clean { withTimeout(5_000) { DefaultNetworkListener.stop(owned) } }
            }
        } finally {
            unmockkObject(Logs, SagerNet.Companion)
        }
        cleanupFailure?.let { throw it }
    }

    @Test
    fun failedRegistrationSkipsUnregisterAndCanRegisterAgain() = runBlocking {
        withTimeout(5_000) {
            val denied = SecurityException("registration denied")
            every {
                connectivity.registerBestMatchingNetworkCallback(
                    any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>(), any<Handler>()
                )
            } throws denied
            val active = mockk<Network>()
            every { connectivity.activeNetwork } returns active
            val failedKey = key()
            DefaultNetworkListener.start(failedKey) {}
            assertSame("Fallback get must observe the active platform network", active, DefaultNetworkListener.get())
            assertTrue(DefaultNetworkListener.stop(failedKey))
            assertFalse(DefaultNetworkListener.stop(failedKey))
            verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
            assertTrue("Registration failure must remain observable", warnings.any { it === denied })

            allowRegistration()
            val recoveredKey = key()
            DefaultNetworkListener.start(recoveredKey) {}
            assertTrue(DefaultNetworkListener.stop(recoveredKey))
            verify(exactly = 1) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        }
    }

    @Test
    fun onlyLastListenerUnregistersAndRepeatedStopIsFalse() = runBlocking {
        withTimeout(5_000) {
            val first = key()
            val second = key()
            DefaultNetworkListener.start(first) {}
            DefaultNetworkListener.start(second) {}
            assertTrue(DefaultNetworkListener.stop(first))
            verify(exactly = 0) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
            assertTrue(DefaultNetworkListener.stop(second))
            verify(exactly = 1) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
            assertFalse(DefaultNetworkListener.stop(second))
            verify(exactly = 1) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        }
    }

    @Test
    fun lastStopCancelsPendingGetAndOldCallbackCannotPopulateNewRegistration() = runBlocking {
        withTimeout(5_000) {
            val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
            every {
                connectivity.registerBestMatchingNetworkCallback(
                    any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>(), any<Handler>()
                )
            } answers { callbacks += secondArg<ConnectivityManager.NetworkCallback>(); Unit }
            val first = key()
            val second = key()
            DefaultNetworkListener.start(first) {}
            DefaultNetworkListener.start(second) {}
            val pending = async(start = CoroutineStart.UNDISPATCHED) { DefaultNetworkListener.get() }
            DefaultNetworkListener.stop(first)
            assertFalse("One remaining listener still owns the pending request", pending.isCompleted)
            DefaultNetworkListener.stop(second)
            pending.join()
            assertTrue("Last stop cancels pending get", pending.isCancelled)

            val next = key()
            DefaultNetworkListener.start(next) {}
            val nextPending = async(start = CoroutineStart.UNDISPATCHED) { DefaultNetworkListener.get() }
            callbacks.first().onAvailable(mockk<Network>())
            assertFalse("Old callback cannot complete a new lifetime's request", nextPending.isCompleted)
            val currentNetwork = mockk<Network>()
            callbacks.last().onAvailable(currentNetwork)
            assertSame(currentNetwork, nextPending.await())
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun listenerFailureIsLoggedWithoutBreakingOtherListenersOrActor() = runBlocking {
        withTimeout(5_000) {
            val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
            every {
                connectivity.registerBestMatchingNetworkCallback(
                    any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>(), any<Handler>()
                )
            } answers { callbacks += secondArg<ConnectivityManager.NetworkCallback>(); Unit }
            val failure = IllegalStateException("consumer callback failed")
            val broken = key()
            val healthy = key()
            var observed: Network? = null
            DefaultNetworkListener.start(broken) { throw failure }
            DefaultNetworkListener.start(healthy) { observed = it }

            val current = mockk<Network>()
            callbacks.single().onAvailable(current)

            assertSame("A failed listener must not suppress later listeners", current, observed)
            assertTrue("The callback failure must remain observable", warnings.any { it === failure })
            assertTrue(DefaultNetworkListener.stop(broken))
            assertTrue("The actor must remain usable after callback failure", DefaultNetworkListener.stop(healthy))
        }
    }

    @Test
    fun getWithoutListenerFailsWithoutBreakingActor() = runBlocking {
        withTimeout(5_000) {
            val failure = try {
                DefaultNetworkListener.get()
                null
            } catch (error: Throwable) {
                error
            }
            assertTrue("Unsupported get must fail at its caller", failure is IllegalStateException)

            val subsequent = key()
            DefaultNetworkListener.start(subsequent) {}
            assertTrue("The actor must remain usable after rejected get", DefaultNetworkListener.stop(subsequent))
        }
    }

    @Test
    fun unregisterFailureReachesDirectCallerButKillContinuesAndActorRemainsUsable() = runBlocking {
        withTimeout(5_000) {
            val directFailure = IllegalArgumentException("callback not registered")
            every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws directFailure
            val directKey = key()
            DefaultNetworkListener.start(directKey) {}
            val received = try { DefaultNetworkListener.stop(directKey); null } catch (error: Throwable) { error }
            assertSame("The stop acknowledgement must preserve its original failure identity", directFailure, originalOrRecoveryCause(directFailure, received))

            every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
            val subsequent = key()
            DefaultNetworkListener.start(subsequent) {}
            assertTrue("The actor must still process a later key after the exception", DefaultNetworkListener.stop(subsequent))

            val killFailure = IllegalArgumentException("unregister failed during kill")
            every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws killFailure
            val fake = service()
            DefaultNetworkListener.start(fake) {}
            fake.killProcesses()
            assertFalse("kill must return to its caller after logging; the key was already removed", DefaultNetworkListener.stop(fake))
            assertTrue("Service cleanup must retain the unregister exception in its log", warnings.any { originalOrRecoveryCause(killFailure, it) === killFailure })

            every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
            val afterKill = key()
            DefaultNetworkListener.start(afterKill) {}
            assertTrue("The actor must also remain usable after service-side logging", DefaultNetworkListener.stop(afterKill))
        }
    }

    @Test
    fun killPropagatesCancellationWithoutLoggingItAsAnOrdinaryFailure() = runBlocking {
        withTimeout(5_000) {
            val fake = service()
            val cancelled = CancellationException("caller cancelled")
            mockkObject(DefaultNetworkListener)
            listenerMocked = true
            coEvery { DefaultNetworkListener.stop(fake) } throws cancelled
            val received = try { fake.killProcesses(); null } catch (error: Throwable) { error }
            assertSame("Cancellation must preserve its original identity", cancelled, originalOrRecoveryCause(cancelled, received))
            assertFalse("Cancellation is not a best-effort cleanup warning", warnings.any { originalOrRecoveryCause(cancelled, it) === cancelled })
            coVerify(exactly = 1) { DefaultNetworkListener.stop(fake) }
        }
    }
    @Test
    fun coreAndListenerCloseFailuresStillReleaseWakeLockAndPreserveBothErrors() = runBlocking {
        withTimeout(5_000) {
            val fake = service()
            val proxy = mockk<ProxyInstance>()
            // Use the real Robolectric WakeLock; MockK cannot safely retransform
            // this instrumented Android final class (run52 failed in mock creation).
            val power = org.robolectric.RuntimeEnvironment.getApplication().getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:cleanup")
            lock.acquire()
            val coreFailure = IllegalStateException("core close failed")
            val listenerFailure = IllegalArgumentException("listener unregister failed")
            every { fake.data.proxy } returns proxy
            every { proxy.close() } throws coreFailure
            every { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws listenerFailure
            fake.wakeLock = lock
            DefaultNetworkListener.start(fake) {}
            val received = try { fake.killProcesses(); null } catch (error: Throwable) { error }
            val original = originalOrRecoveryCause(coreFailure, received)
            assertSame(coreFailure, original)
            assertTrue("Secondary cleanup failure remains attached", coreFailure.suppressed.any { originalOrRecoveryCause(listenerFailure, it) === listenerFailure })
            assertTrue("Wake lock reference cleared despite core failure", fake.wakeLock == null)
            assertFalse("Wake lock released despite core failure", lock.isHeld)
            assertFalse("Core failure must not leave the listener registered", DefaultNetworkListener.stop(fake))
            verify(exactly = 1) { proxy.close() }
            verify(exactly = 1) { connectivity.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
        }
    }

}
