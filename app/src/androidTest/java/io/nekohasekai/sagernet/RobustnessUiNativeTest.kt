package io.nekohasekai.sagernet

import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.ui.AppListActivity
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RobustnessUiNativeTest {
    @Test
    fun appListLoadingCompletesAcrossActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n $packageName/io.nekohasekai.sagernet.ui.MainActivity"
        ).close()
        SystemClock.sleep(500)
        ActivityScenario.launch(AppListActivity::class.java).use { scenario ->
            awaitLoaded(scenario)
            scenario.recreate()
            awaitLoaded(scenario)
        }
    }

    private fun awaitLoaded(scenario: ActivityScenario<AppListActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val loaded = AtomicBoolean()
            scenario.onActivity { activity ->
                loaded.set(activity.findViewById<View>(R.id.loading).visibility != View.VISIBLE)
            }
            if (loaded.get()) return
            SystemClock.sleep(100)
        }
        error("App list did not leave its loading state")
    }
}
