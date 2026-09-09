package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Keep one inert debug Activity resumed for the entire benchmark, outside measured work. */
class BenchmarkForegroundRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            // Some vendor builds suppress app-originated background Activity launches,
            // even under instrumentation. Shell starts this debug-only inert host first.
            // No app-op, power policy or system setting is modified.
            val component = "${context.packageName}/io.nekohasekai.sagernet.BenchmarkHostActivity"
            val launch = instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
            val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(launch)
                .bufferedReader().use { it.readText() }
            check(!output.contains("Error:") && !output.contains("Exception")) { output }
            val intent = Intent().setClassName(context.packageName,
                "io.nekohasekai.sagernet.BenchmarkHostActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                check(scenario.state == Lifecycle.State.RESUMED) { "Benchmark host did not resume" }
                Log.i("BenchmarkForeground", "case=${description.methodName} stage=before state=RESUMED")
                base.evaluate()
                check(scenario.state == Lifecycle.State.RESUMED) { "Benchmark host lost foreground" }
                Log.i("BenchmarkForeground", "case=${description.methodName} stage=after state=RESUMED")
            }
        }
    }
}
