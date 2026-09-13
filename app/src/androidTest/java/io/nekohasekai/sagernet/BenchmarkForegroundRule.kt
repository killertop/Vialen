package io.nekohasekai.sagernet

import android.app.Activity
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** An inert debug Activity starts in foreground; UI scenarios may replace its task. */
class BenchmarkForegroundRule(private val requireRetainedHost: Boolean = true) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val className = "io.nekohasekai.sagernet.BenchmarkHostActivity"
            var host: Activity? = null
            fun resumed() = instrumentation.runOnMainSync {
                val activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filter { it.javaClass.name == className }
                check(activities.size == 1) { "Expected one resumed benchmark host" }
                val current = activities.single()
                check(host == null || host === current) { "Benchmark host was replaced" }
                host = current
            }
            try {
                // Vendor builds can suppress app-originated background launches, including
                // ActivityScenario's cleanup EmptyActivity. Use shell only to open this host;
                // no app-op, power policy or system setting changes are needed.
                val command = "am start -W -n ${context.packageName}/$className"
                val output = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command))
                    .bufferedReader().use { it.readText() }
                check(!output.contains("Error:") && !output.contains("Exception")) { output }
                instrumentation.waitForIdleSync()
                // am start can return before the lifecycle monitor observes RESUMED.
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                while (true) {
                    val observed = runCatching { resumed() }
                    if (observed.isSuccess) break
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        throw checkNotNull(observed.exceptionOrNull())
                    }
                    Thread.sleep(50)
                }
                Log.i("BenchmarkForeground", "case=${description.methodName} stage=before state=RESUMED")
                base.evaluate()
                if (requireRetainedHost) {
                    resumed()
                    Log.i("BenchmarkForeground", "case=${description.methodName} stage=after state=RESUMED")
                }
            } finally {
                instrumentation.runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    // Include a paused launch if startup failed before assigning host.
                    val owned = Stage.values().flatMap { monitor.getActivitiesInStage(it) }
                        .filter { it.javaClass.name == className }.distinct()
                    owned.forEach { it.finishAndRemoveTask() }
                }
            }
        }
    }
}
