package io.nekohasekai.sagernet

import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.utils.RuntimeDiagnostics
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Real JNI, current process only. No Activity, nodes, VPN or ten-minute wait.
 * Log mutation is permitted only in the separately installed Debug application's sandbox.
 * Go tests cover expiry with an injected clock, logger filtering and cross-process file locking.
 */
@RunWith(AndroidJUnit4::class)
class RuntimePolicyNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val limit = 256 * 1024

    @Before fun requireIsolatedDebugApplication() {
        // Must precede ALL native calls, including clear and diagnostic teardown.
        assumeTrue("Requires separately installed Debug app; never mutate Release logs",
            BuildConfig.DEBUG && context.packageName == BuildConfig.APPLICATION_ID &&
                context.packageName.endsWith(".debug") &&
                (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
            .executeShellCommand("dumpsys activity services ${context.packageName}"))
            .bufferedReader().use { it.readText() }
        check(dump.contains("ACTIVITY MANAGER SERVICES")) { "Cannot verify isolated service state" }
        check(!dump.contains("startRequested=true") && !dump.contains("fgRequired=true") &&
            !dump.contains("isForeground=true")) { "Stop the Debug session before this test" }
    }

    @Test fun diagnosticSessionCanBeEnabledAndCancelledThroughJni() {
        try {
            Libcore.setDiagnosticMode(false)
            assertEquals(0L, Libcore.diagnosticRemainingMillis())
            assertEquals("Normal ConfigSnapshot policy is info", 2, RuntimeDiagnostics.NORMAL_LOG_LEVEL)
            Libcore.setDiagnosticMode(true)
            val initial = Libcore.diagnosticRemainingMillis()
            assertTrue("Explicit session must be bounded to ten minutes", initial in 1L..600_000L)
            SystemClock.sleep(30)
            val later = Libcore.diagnosticRemainingMillis()
            assertTrue("Monotonic session countdown must decrease", later in 1 until initial)
            Libcore.setDiagnosticMode(false)
            assertEquals(0L, Libcore.diagnosticRemainingMillis())
            // A new explicit request can start again after cancellation; nothing is persisted.
            Libcore.setDiagnosticMode(true)
            assertTrue(Libcore.diagnosticRemainingMillis() in 1L..600_000L)
        } finally {
            Libcore.setDiagnosticMode(false)
            assertEquals("Always leave this process out of diagnostic mode", 0L,
                Libcore.diagnosticRemainingMillis())
        }
    }

    @Test fun nativeLogIsBoundedReadableAndClearableInDebugSandbox() {
        val log = File(context.cacheDir, "neko.log")
        val marker = "runtime-policy-${System.nanoTime()}"
        try {
            Libcore.setDiagnosticMode(false)
            assertEquals(256, RuntimeDiagnostics.LOG_CAPACITY_KIB)
            // Exercise both a single oversized write and accumulated small writes.
            Libcore.nekoLogPrintln("x".repeat(limit + 4096) + marker)
            assertTrue("Native rolling writer must create a bounded file", log.isFile && log.length() <= limit)
            repeat(80) { Libcore.nekoLogPrintln("y".repeat(4096)) }
            Libcore.nekoLogPrintln(marker)
            assertTrue(log.length() in 1L..limit.toLong())
            // Bound the reader too, even if a regression writes an oversized file.
            val bytes = ByteArray(limit + 1)
            val count = log.inputStream().use { input ->
                var total = 0
                while (total < bytes.size) {
                    val read = input.read(bytes, total, bytes.size - total)
                    if (read < 0) break
                    total += read
                }
                total
            }
            assertTrue("Log export must stay within 256 KiB", count <= limit)
            assertTrue(String(bytes, 0, count, Charsets.UTF_8).contains(marker))
            Libcore.nekoLogClear()
            assertEquals("Clear truncates the shared log", 0L, log.length())
            Libcore.nekoLogPrintln("after-clear-$marker")
            assertTrue("Logging must continue after clear", log.length() in 1L..limit.toLong())
        } finally {
            // Nested cleanup guarantees diagnostic shutdown even if log clearing fails.
            try { Libcore.nekoLogClear() } finally { Libcore.setDiagnosticMode(false) }
        }
    }

    @Test fun settingsExposeManagedPoliciesWithoutLegacyControls() {
        try {
            val keys = mutableSetOf<String>()
            context.resources.getXml(R.xml.global_preferences).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        parser.getAttributeValue("http://schemas.android.com/apk/res-auto", "key")
                            ?.let(keys::add)
                    }
                    parser.next()
                }
            }
            for (removed in listOf("speedInterval", "appTLSVersion", "logLevel", "uiLogBuffer")) {
                assertFalse("Legacy setting still visible: $removed", removed in keys)
            }
            for (retained in listOf("uiDetailedDiagnostics", "uiManagedSettings", "nightTheme",
                "allowInsecureOnRequest", "profileTrafficStatistics")) {
                assertTrue("Missing retained control: $retained", retained in keys)
            }
        } finally {
            Libcore.setDiagnosticMode(false)
        }
    }
}
