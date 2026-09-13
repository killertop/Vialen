package io.nekohasekai.sagernet

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Physical UI timing gate backed by an isolated local URL-test endpoint. */
@RunWith(AndroidJUnit4::class)
class UrlTestDialogTimingNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE)
    val foreground = BenchmarkForegroundRule(requireRetainedHost = false)
    @get:org.junit.Rule val state = ProfileSelectionStateRule()

    @Test fun finishedDialogRemainsVisibleThenAutoHides() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires -e vialenUrlDialogTiming true",
            InstrumentationRegistry.getArguments().getString("vialenUrlDialogTiming") == "true")
        check(Build.FINGERPRINT.let {
            !it.contains("generic") && !it.contains("emulator") && !it.contains("sdk_gphone")
        }) { "Physical device required" }
        check(!DataStore.serviceState.started) { "VPN service must be stopped" }

        val app = instrumentation.targetContext
        val db = SagerDatabase.instance
        val preferences = PublicDatabase.kvPairDao
        val keys = listOf(Key.CONNECTION_TEST_URL, "connectionTestConcurrent",
            "managedRuntimeNoticeAcknowledged", "isAutoConnect")
        val saved = keys.associateWith { key -> preferences[key]?.let { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        } }
        val nonce = "url-dialog-${System.nanoTime()}"
        val fixture = LoopbackSocksFixture(nonce)
        var groupId = 0L
        var proxyId = 0L
        var scenario: ActivityScenario<MainActivity>? = null
        val output = File(checkNotNull(app.getExternalFilesDir(null)), "url-dialog-timing/${System.currentTimeMillis()}")
        check(output.mkdirs())
        var failure: Throwable? = null
        try {
            val group = ProxyGroup(name = "URL timing fixture")
            groupId = db.groupDao().createGroup(group)
            val profile = ProxyEntity(groupId = groupId).apply {
                putBean(SOCKSBean().applyDefaultValues().apply {
                    name = "Local URL timing"
                    serverAddress = "127.0.0.1"
                    serverPort = fixture.port
                })
            }
            proxyId = db.proxyDao().addProxy(profile)
            DataStore.selectedGroup = groupId
            DataStore.selectedProxy = proxyId
            DataStore.connectionTestURL = "http://198.18.0.254/$nonce"
            DataStore.connectionTestConcurrent = 1
            DataStore.configurationStore.putBoolean("managedRuntimeNoticeAcknowledged", true)
            DataStore.configurationStore.putBoolean("isAutoConnect", false)

            scenario = ActivityScenario.launch(MainActivity::class.java)
            instrumentation.waitForIdleSync()
            var invokedAt = 0L
            scenario.onActivity { activity ->
                activity.displayFragmentWithId(R.id.nav_configuration)
                activity.supportFragmentManager.executePendingTransactions()
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                    as ConfigurationFragment
                invokedAt = SystemClock.elapsedRealtime()
                fragment.urlTest()
            }
            val title = listOf("URL 测试", "URL test")
            val finished = listOf("测试完成", "Test complete")
            await("finished URL dialog") { hasAnyText(title) && hasAnyText(finished) }
            val finishedAt = SystemClock.elapsedRealtime()
            // Accessibility text updates before SurfaceFlinger presents the next frame.
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            check(hasAnyText(finished)) { "Completion disappeared before the capture" }
            screenshot(File(output, "finished.png"))
            await("URL dialog dismissal") { !hasAnyText(title) }
            val dismissedAt = SystemClock.elapsedRealtime()
            val visibleMillis = dismissedAt - finishedAt
            val totalMillis = dismissedAt - invokedAt
            assertTrue("Finished state visible too briefly: ${visibleMillis}ms", visibleMillis >= 1_000)
            assertTrue("Finished state visible too long: ${visibleMillis}ms", visibleMillis <= 1_800)
            // libneko RTT mode performs a warm-up and a measured GET.
            assertTrue("Expected two RTT requests: ${fixture.diagnosticSnapshot()}", fixture.requests.get() == 2)
            File(output, "timing.txt").writeText(
                "finished_visible_ms=$visibleMillis\ntotal_ms=$totalMillis\nrequests=${fixture.requests.get()}\n")
            println("URL_DIALOG_TIMING finished_visible_ms=$visibleMillis total_ms=$totalMillis requests=${fixture.requests.get()} output=${output.absolutePath}")
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { scenario?.onActivity { it.finishAndRemoveTask() } }
                .onFailure { if (failure == null) failure = it else failure!!.addSuppressed(it) }
            runCatching { fixture.close() }
                .onFailure { if (failure == null) failure = it else failure!!.addSuppressed(it) }
            runCatching {
                db.runInTransaction {
                    if (proxyId != 0L) db.proxyDao().deleteById(proxyId)
                    if (groupId != 0L) db.groupDao().deleteById(groupId)
                }
                saved.forEach { (key, row) -> if (row == null) preferences.delete(key) else preferences.put(row) }
            }.onFailure { if (failure == null) failure = it else failure!!.addSuppressed(it) }
        }
        failure?.let { throw it }
    }

    private fun hasAnyText(values: List<String>): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return values.any { value -> root.findAccessibilityNodeInfosByText(value).any {
            it.isVisibleToUser && it.text?.toString() == value
        } }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Deadline waiting for $label" }
            Thread.sleep(20)
        }
    }

    private fun screenshot(file: File) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
