package io.nekohasekai.sagernet

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in screenshots of synthetic visual states, never a connectivity test. */
@RunWith(AndroidJUnit4::class)
class VisualConnectionCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun captureAttachedConnectionStates() {
        assumeTrue("Requires -e vialenVisualCapture true", InstrumentationRegistry.getArguments()
            .getString("vialenVisualCapture") == "true")
        checkNoStartedService()
        onMain {
            check(Stage.values().filter { it != Stage.DESTROYED }.all {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).isEmpty()
            }) { "Close app activities before this isolated capture" }
        }
        val dao = PublicDatabase.kvPairDao
        val before = dao.all().map { row -> KeyValuePair(row.key).also {
            it.valueType = row.valueType; it.value = row.value.copyOf()
        } }
        val oldState = DataStore.serviceState
        val output = File(checkNotNull(context.getExternalFilesDir(null)),
            "ui-v1.6-connection/${System.currentTimeMillis()}")
        check(output.mkdirs())
        val captures = JSONArray()
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        try {
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            run {
                val label = "light"
                val scenario = ActivityScenario.launch(MainActivity::class.java)
                try {
                    settle(750)
                    lateinit var activity: MainActivity
                    scenario.onActivity { activity = it }
                    var previous = State.Stopped
                    fun show(state: State, delayed: Boolean = false) {
                        onMain {
                            check(activity.binding.fab.isAttachedToWindow)
                            activity.binding.fab.changeState(state, previous, false)
                            activity.binding.stats.allowShow = true
                            activity.binding.stats.changeState(state)
                        }
                        previous = state
                        settle(if (delayed) context.resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() + 1200 else 350)
                        onMain {
                            check(activity.binding.fab.isEnabled == (state != State.Stopping))
                            check(activity.binding.fab.drawableState.contains(android.R.attr.state_checked) == (state == State.Connected))
                            if (delayed) check(activity.binding.fabProgress.isIndeterminate)
                            if (state == State.Connected) {
                                // Explicitly synthetic rates; no speed callbacks or network test.
                                activity.binding.stats.updateSpeed(12_288, 49_152)
                            }
                        }
                        instrumentation.waitForIdleSync()
                        val name = "$label-${state.name.lowercase()}${if (delayed) "-progress" else ""}.png"
                        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try { File(output, name).outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        } } finally { bitmap.recycle() }
                        captures.put(JSONObject().put("file", name).put("state", state.name)
                            .put("theme", label).put("synthetic", true))
                    }
                    show(State.Stopped)
                    show(State.Connecting)
                    show(State.Connecting, delayed = true)
                    show(State.Connected)
                    show(State.Stopping)
                    onMain {
                        activity.binding.fab.changeState(State.Stopped, State.Stopping, false)
                        activity.binding.stats.changeState(State.Stopped)
                    }
                } finally { scenario.close(); settle(350) }
                checkNoStartedService()
            }
        } catch (error: Throwable) { failure = error } finally {
            cleanup {
                val keys = before.map { it.key }.toSet()
                dao.all().filter { it.key !in keys }.forEach { dao.delete(it.key) }
                before.forEach { dao.put(it) }
                val actual = dao.all().associateBy { it.key }
                check(actual.keys == keys)
                before.forEach { row -> check(actual[row.key]?.let {
                    it.valueType == row.valueType && it.value.contentEquals(row.value)
                } == true) { "Preference restore mismatch: ${row.key}" } }
            }
            cleanup {
                DataStore.serviceState = oldState
            }
            cleanup { checkNoStartedService() }
            cleanup {
                File(output, "capture-index.json").writeText(JSONObject()
                    .put("meaning", "Synthetic visual states only; no VPN was started and no connectivity or release PASS is implied")
                    .put("status", if (failure == null) "CAPTURED" else "FAILED")
                    .put("screenshots", captures).put("failure", failure?.toString() ?: JSONObject.NULL).toString(2))
                println("VIALEN_CONNECTION_CAPTURE ${output.absolutePath} count=${captures.length()}")
            }
        }
        failure?.let { throw it }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun settle(milliseconds: Long) {
        instrumentation.waitForIdleSync(); Thread.sleep(milliseconds); instrumentation.waitForIdleSync()
    }
    private fun checkNoStartedService() {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "dumpsys activity services ${context.packageName}")).bufferedReader().use { it.readText() }
        check(dump.contains("ACTIVITY MANAGER SERVICES")) { "Cannot verify stopped service" }
        check(!dump.contains("startRequested=true") && !dump.contains("fgRequired=true") && !dump.contains("isForeground=true")) {
            "Capture requires stopped service; it will not stop an existing VPN"
        }
    }
}
