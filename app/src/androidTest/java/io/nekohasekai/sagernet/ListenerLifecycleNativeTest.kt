package io.nekohasekai.sagernet

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises listener ownership without starting a VPN, service, notification or Activity. */
@RunWith(AndroidJUnit4::class)
class ListenerLifecycleNativeTest {
    private class FakeService : BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "ListenerLifecycleFake"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null
        override fun createNotification(profileName: String): ServiceNotification =
            error("The listener-only test must not create a notification")
        override fun acquireWakeLock() = Unit
    }

    @Test
    fun killProcessesRemovesOwnListenerBeforeReturningAndPreservesOtherKeys() = runBlocking {
        val service = FakeService()
        val otherKey = Any()
        // Reuse failure-preserving cleanup only; no profile-selection rule is applied.
        val failures = ProfileSelectionStateRule()
        failures.preservingFailure({
            withTimeout(5_000) {
                DefaultNetworkListener.start(service) {}
                DefaultNetworkListener.start(otherKey) {}

                service.killProcesses()
                val removedAfterKill = DefaultNetworkListener.stop(service)
                val otherWasPresent = DefaultNetworkListener.stop(otherKey)
                service.killProcesses()
                val removedAfterRepeatedKill = DefaultNetworkListener.stop(service)

                println("LISTENER_LIFECYCLE removed_after_kill=$removedAfterKill other_key_present=$otherWasPresent removed_after_repeated_kill=$removedAfterRepeatedKill")
                assertFalse("killProcesses must remove its service listener before returning", removedAfterKill)
                assertTrue("killProcesses must preserve a different listener key", otherWasPresent)
                assertFalse("Repeated killProcesses must be idempotent", removedAfterRepeatedKill)
            }
        }, {
            failures.cleanupSteps({
                withTimeout(5_000) { DefaultNetworkListener.stop(service) }
            }, {
                withTimeout(5_000) { DefaultNetworkListener.stop(otherKey) }
            }, {
                service.data.binder.close()
            })
        })
    }
}
