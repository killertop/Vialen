package io.nekohasekai.sagernet

import android.app.Service
import android.content.BroadcastReceiver
import android.os.PowerManager
import io.mockk.*
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.*
import moe.matsuri.nb4a.TempDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the public stopRunner and a fresh Service, including the actual state transitions. */
@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ServiceStopFailureTest {
    class TestService : Service(), BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "ServiceStopFailureTest"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null
        var closeFailure: Throwable? = null
        var unregisterFailure: Throwable? = null
        var killCalls = 0
        var unregisterCalls = 0
        var starts = 0
        var notifications = 0
        var extraStopError: String? = null
        override fun createNotification(profileName: String): ServiceNotification {
            notifications++
            return mockk(relaxed = true)
        }
        override fun acquireWakeLock() = Unit
        override suspend fun killProcesses() {
            killCalls++
            closeFailure?.let { throw it }
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            unregisterCalls++
            unregisterFailure?.let { throw it }
        }
        override fun startRunner() { starts++ }
        override fun stopError() = extraStopError
        override fun onBind(intent: android.content.Intent) = super<BaseService.Interface>.onBind(intent)
        override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int) =
            super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    }

    private val services = mutableListOf<TestService>()
    private val failures = mutableListOf<Throwable>()

    @Before fun setup() {
        val preferences = mockk<KeyValuePair.Dao>(relaxed = true)
        every { preferences[any()] } returns null
        mockkObject(PublicDatabase.Companion, TempDatabase.Companion)
        every { PublicDatabase.kvPairDao } returns preferences
        every { TempDatabase.profileCacheDao } returns preferences
        mockkObject(DataStore, Logs)
        every { Logs.w(any<Throwable>()) } answers { failures.add(firstArg()); Unit }
        mockkStatic("io.nekohasekai.sagernet.ktx.AsyncsKt")
        every { runOnMainDispatcher(any()) } answers {
            CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED, block = firstArg())
        }
        BaseService.cleanupFailure = null
    }

    private fun service() = Robolectric.buildService(TestService::class.java).create().get().also {
        services.add(it)
        it.data.state = BaseService.State.Connected
        it.data.notification = mockk(relaxed = true)
        it.data.closeReceiverRegistered = true
    }

    @After fun cleanup() {
        services.forEach { it.data.binder.close() }
        BaseService.cleanupFailure = null
        unmockkAll()
    }

    @Test fun closeFailureStillUnregistersStopsAndBlocksAutomaticAndFreshServiceRestart() {
        val service = service()
        val notification = service.data.notification!!
        service.closeFailure = IllegalStateException("final traffic save failed")
        service.stopRunner(restart = true)
        assertEquals(BaseService.State.Stopped, service.data.state)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(1, service.unregisterCalls)
        assertFalse(service.data.closeReceiverRegistered)
        assertNull(service.data.proxy)
        assertNull(service.data.notification)
        assertNull(service.data.connectingJob)
        assertEquals(0, service.starts)
        assertNotNull(BaseService.cleanupFailure)
        verify(exactly = 1) { notification.destroy() }
        assertEquals(1, failures.size)

        val fresh = service()
        fresh.data.state = BaseService.State.Stopped
        fresh.data.closeReceiverRegistered = false
        // Gate runs before profile/database lookup or core construction, even on another Service object.
        fresh.onStartCommand(null, 0, 2)
        assertEquals(1, fresh.notifications)
        assertEquals(0, fresh.starts)
        assertEquals(BaseService.State.Stopped, fresh.data.state)
        assertTrue(shadowOf(fresh).isStoppedBySelf)
    }

    @Test fun cancellationAndReceiverFailureCannotSkipTheRemainingStopSteps() {
        val service = service()
        service.closeFailure = CancellationException("cancelled during final save")
        service.unregisterFailure = IllegalArgumentException("receiver cleanup failed")
        service.stopRunner(restart = true)
        assertEquals(BaseService.State.Stopped, service.data.state)
        assertFalse(service.data.closeReceiverRegistered)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals(0, service.starts)
        assertNotNull(BaseService.cleanupFailure)
        assertTrue(failures.single().suppressed.contains(service.unregisterFailure))
    }

    @Test fun cleanStopRestartsOnceAndVpnStopIssueStillDeniesRestart() {
        val service = service()
        service.stopRunner(restart = true)
        assertEquals(BaseService.State.Stopped, service.data.state)
        assertEquals(1, service.starts)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNull(BaseService.cleanupFailure)
        assertTrue(failures.isEmpty())

        val waiting = service()
        waiting.extraStopError = "Waiting for VPN removal"
        waiting.stopRunner(restart = true)
        assertEquals(0, waiting.starts)
        assertTrue(shadowOf(waiting).isStoppedBySelf)
        assertNull("A pending VPN removal must retain its own recoverable gate", BaseService.cleanupFailure)
    }
}
