package io.nekohasekai.sagernet

import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reject/cancel consent must not arm an unfulfilled foreground-service deadline. */
@RunWith(AndroidJUnit4::class)
class VpnConsentNativeTest {
    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()

    @Test fun unpreparedStartKeepsBoundServiceAliveWithoutStartingForegroundService() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as SagerNet
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
        fun logConsentState(stage: String) {
            val appOp = shell("cmd appops get ${app.packageName} ACTIVATE_VPN")
            val prepared = VpnService.prepare(app) == null
            println("VPN_CANCEL_DIAGNOSTIC stage=$stage prepareIsNull=$prepared appops=$appOp")
        }
        val oldMode = DataStore.serviceMode
        val oldAppOp = shell("cmd appops get ${app.packageName} ACTIVATE_VPN")
            .substringAfter("ACTIVATE_VPN: ", "default").substringBefore(';').trim()
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        profileState.preservingFailure({
            VpnConsentTestUi.assertClean()
            DataStore.serviceMode = Key.MODE_VPN
            shell("cmd appops set ${app.packageName} ACTIVATE_VPN deny")
            assertNotNull("This test requires denied consent", VpnService.prepare(app))
            VpnConsentTestUi.launchMainResumed()
            connection.connect(app, object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
                override fun onServiceConnected(service: ISagerNetService) {}
            })
            val bindDeadline = System.nanoTime() + 5_000_000_000L
            while (connection.service == null && System.nanoTime() < bindDeadline) delay(50)
            val binder = checkNotNull(connection.service).asBinder()
            assertTrue(binder.isBinderAlive)
            SagerNet.startService()
            val owner=VpnConsentTestUi.awaitFreshDialog()
            val services = shell("dumpsys activity services ${app.packageName}")
            assertFalse("A foreground service must not start before VPN consent: $services",
                services.contains("startRequested=true") || services.contains("fgRequired=true"))
            // ConfirmDialog intentionally ignores BACK. Exercise its actual negative action.
            logConsentState("before_button2")
            VpnConsentTestUi.clickButton("android:id/button2")
            VpnConsentTestUi.awaitDismissed(owner)
            logConsentState("after_dismissed")
            delay(12_000) // Longer than the reproduced foreground-start deadline.
            logConsentState("after_12_seconds")
            assertNotEquals("VPN dialog must have been dismissed by cancel", "com.android.vpndialogs", instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            assertNotNull("No consent was granted", VpnService.prepare(app))
            assertTrue("Bound process must survive denied/cancelled consent", binder.pingBinder())
            assertEquals(BaseService.State.Stopped.ordinal, checkNotNull(connection.service).state)
            val after=shell("dumpsys activity services ${app.packageName}")
            assertFalse("Cancel must never start foreground service: $after",
                after.contains("startRequested=true") || after.contains("fgRequired=true"))
            println("VPN_CANCEL_SERVICE_DUMP\n$after")
            println("VPN_CONSENT denied=true foreground_start=false binder_survived=true")
        }, {
            profileState.cleanupSteps({
                VpnConsentTestUi.cleanup()
            }, {
                profileState.stopAndAwait(connection)
            }, {
                connection.disconnect(app)
            }, {
                DataStore.serviceMode = oldMode
                shell("cmd appops set ${app.packageName} ACTIVATE_VPN $oldAppOp")
            })
        })
    }
}
