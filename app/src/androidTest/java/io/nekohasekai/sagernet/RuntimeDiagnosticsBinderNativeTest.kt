package io.nekohasekai.sagernet

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.utils.RuntimeDiagnostics
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit physical acceptance; binds an idle Debug service, never starts a VPN or edits data.
 * The expiry case uses the real ten-minute deadline, without clock or display setting changes.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeDiagnosticsBinderNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    private fun withIdleRemote(body: (ISagerNetService) -> Unit) {
        assumeTrue(BuildConfig.DEBUG && app.packageName.endsWith(".debug"))
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
            .executeShellCommand("dumpsys activity services ${app.packageName}"))
            .bufferedReader().use { it.readText() }
        check(dump.contains("ACTIVITY MANAGER SERVICES"))
        check(!dump.contains("startRequested=true") && !dump.contains("isForeground=true") &&
            !dump.contains("fgRequired=true")) { "Stop Debug VPN before diagnostic acceptance" }
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        var remote: ISagerNetService? = null
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync {
                connection.connect(app, object : SagerConnection.Callback {
                    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit
                    override fun onServiceConnected(service: ISagerNetService) = Unit
                })
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (remote == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { remote = connection.service }
                if (remote == null) SystemClock.sleep(50)
            }
            val service = checkNotNull(remote) { "Private service binding timed out" }
            assertEquals("Must cross a real process boundary", "android.os.BinderProxy",
                service.asBinder().javaClass.name)
            RuntimeDiagnostics.setEnabled(service, false)
            body(service)
        } catch (error: Throwable) {
            failure = error
        } finally {
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            cleanup {
                remote?.setDiagnosticMode(false)
                remote?.let { assertEquals(0L, it.diagnosticRemainingMillis) }
            }
            cleanup { Libcore.setDiagnosticMode(false); assertEquals(0L, Libcore.diagnosticRemainingMillis()) }
            cleanup { instrumentation.runOnMainSync { connection.disconnect(app) } }
        }
        failure?.let { throw it }
    }

    @Test fun explicitStartStopAndRemoteOnlySessionAreAcknowledged() = withIdleRemote { service ->
        RuntimeDiagnostics.setEnabled(service, true)
        assertTrue(Libcore.diagnosticRemainingMillis() in 1L..600_000L)
        assertTrue(service.diagnosticRemainingMillis in 1L..600_000L)
        // Model a fresh local logger while the existing background session remains alive.
        // This is not a process-kill test; it checks the UI's real remote-only status path.
        Libcore.setDiagnosticMode(false)
        assertEquals(0L, Libcore.diagnosticRemainingMillis())
        assertTrue(RuntimeDiagnostics.remainingMillis(service) in 1L..600_000L)
        RuntimeDiagnostics.setEnabled(service, false)
        assertEquals(0L, service.diagnosticRemainingMillis)
        assertEquals(0L, RuntimeDiagnostics.remainingMillis(service))
        println("DIAGNOSTIC_BINDER remote_process=true start_ack=true remote_only_status=true stop_ack=true")
    }

    @Test fun realTenMinuteDeadlineExpiresInBothProcesses() {
        assumeTrue("Explicit ten-minute physical run required",
            InstrumentationRegistry.getArguments().getString("vialenDiagnosticExpiry") == "true")
        withIdleRemote { service ->
            val elapsedStart = SystemClock.elapsedRealtime()
            val uptimeStart = SystemClock.uptimeMillis()
            RuntimeDiagnostics.setEnabled(service, true)
            assertTrue(Libcore.diagnosticRemainingMillis() in 599_000L..600_000L)
            assertTrue(service.diagnosticRemainingMillis in 599_000L..600_000L)
            var previous = 600_000L
            while (SystemClock.elapsedRealtime() - elapsedStart < 610_000) {
                val remaining = RuntimeDiagnostics.remainingMillis(service)
                assertTrue("Countdown must not extend", remaining <= previous)
                previous = remaining
                if (remaining == 0L) break
                SystemClock.sleep(1_000)
            }
            val elapsed = SystemClock.elapsedRealtime() - elapsedStart
            val awake = SystemClock.uptimeMillis() - uptimeStart
            assertTrue("Must exercise the full real deadline", elapsed >= 599_000)
            assertEquals(0L, Libcore.diagnosticRemainingMillis())
            assertEquals(0L, service.diagnosticRemainingMillis)
            println("DIAGNOSTIC_EXPIRY elapsed_ms=$elapsed uptime_ms=$awake suspend_ms=${elapsed - awake} local=0 remote=0")
        }
    }
}
