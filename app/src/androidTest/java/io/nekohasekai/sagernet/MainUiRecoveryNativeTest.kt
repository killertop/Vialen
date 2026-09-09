package io.nekohasekai.sagernet

import android.os.Binder
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.matsuri.nb4a.TempDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic service results exercise production UI; never a VPN/connectivity acceptance test. */
@RunWith(AndroidJUnit4::class)
class MainUiRecoveryNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var config: List<KeyValuePair>
    private lateinit var cache: List<KeyValuePair>
    private var oldState = State.Idle
    private val releases = mutableListOf<CountDownLatch>()

    private fun copy(rows: List<KeyValuePair>) = rows.map { row -> KeyValuePair(row.key).also {
        it.valueType = row.valueType; it.value = row.value.copyOf()
    } }

    @Before fun preserveState() {
        Assume.assumeTrue("Opt in on a physical device with -e vialenMainUi true",
            InstrumentationRegistry.getArguments().getString("vialenMainUi") == "true")
        check(DataStore.serviceState == State.Idle || DataStore.serviceState == State.Stopped) {
            "Stop VPN before running this isolated UI test"
        }
        oldState = DataStore.serviceState
        config = copy(PublicDatabase.kvPairDao.all())
        cache = copy(TempDatabase.profileCacheDao.all())
        DataStore.configurationStore.putBoolean("isAutoConnect", false)
    }

    @After fun restoreState() {
        releases.forEach { it.countDown() }
        if (::config.isInitialized) {
            PublicDatabase.kvPairDao.reset(); config.forEach { PublicDatabase.kvPairDao.put(it) }
            TempDatabase.profileCacheDao.reset(); cache.forEach { TempDatabase.profileCacheDao.put(it) }
            DataStore.serviceState = oldState
        }
    }

    private fun withMain(block: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            isolate(scenario)
            block(scenario)
        } finally {
            // Release blocking fake calls before lifecycle teardown, including assertion failures.
            releases.forEach { it.countDown() }
            scenario.onActivity {
                it.connection.disconnect(it)
                it.stateChanged(State.Stopped, null, null)
            }
            instrumentation.waitForIdleSync()
            scenario.close()
        }
    }

    private fun isolate(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.connection.disconnect(it) }
        instrumentation.waitForIdleSync() // Drain callbacks captured before unbind.
        scenario.onActivity { it.connection.disconnect(it); it.stateChanged(State.Stopped, null, null) }
    }

    private fun awaitView(scenario: ActivityScenario<MainActivity>, matches: (MainActivity) -> Boolean) {
        val done = CountDownLatch(1)
        var watched: View? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        scenario.onActivity { activity ->
            val root = activity.binding.root
            watched = root
            listener = ViewTreeObserver.OnPreDrawListener {
                if (matches(activity)) done.countDown()
                true
            }
            root.viewTreeObserver.addOnPreDrawListener(listener)
            if (matches(activity)) done.countDown() else root.invalidate()
        }
        try { assertTrue("Expected UI state arrived", done.await(10, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync {
            watched?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        } }
    }

    @Test fun settingsAndBottomBarPreferenceSurviveRecreation() = withMain { scenario ->
        for (show in listOf(false, true)) {
            scenario.onActivity {
                DataStore.showBottomBar = show
                it.displayFragmentWithId(R.id.nav_settings)
                it.supportFragmentManager.executePendingTransactions()
            }
            awaitView(scenario) { it.binding.fab.visibility == if (show) View.VISIBLE else View.GONE }
            scenario.recreate()
            isolate(scenario)
            awaitView(scenario) {
                it.supportFragmentManager.findFragmentById(R.id.fragment_holder) is SettingsFragment &&
                    it.binding.fab.visibility == if (show) View.VISIBLE else View.GONE
            }
            scenario.onActivity {
                assertTrue(it.supportFragmentManager.findFragmentById(R.id.fragment_holder) is SettingsFragment)
                assertEquals(show, it.binding.stats.allowShow)
            }
        }
    }

    @Test fun backClosesDrawerBeforeLeavingSettings() = withMain { scenario ->
        val closed = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.displayFragmentWithId(R.id.nav_settings)
            activity.supportFragmentManager.executePendingTransactions()
            activity.binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
                override fun onDrawerClosed(drawerView: View) { closed.countDown() }
            })
            activity.binding.drawerLayout.openDrawer(GravityCompat.START, false)
            assertTrue(activity.binding.drawerLayout.isDrawerOpen(GravityCompat.START))
            activity.onBackPressedDispatcher.onBackPressed()
        }
        assertTrue("Drawer closes", closed.await(10, TimeUnit.SECONDS))
        scenario.onActivity {
            assertFalse(it.binding.drawerLayout.isDrawerOpen(GravityCompat.START))
            assertTrue(it.supportFragmentManager.findFragmentById(R.id.fragment_holder) is SettingsFragment)
            assertFalse(it.isFinishing)
        }
    }

    private class ControlledCall(val fail: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service: ISagerNetService = Proxy.newProxyInstance(
            ISagerNetService::class.java.classLoader, arrayOf(ISagerNetService::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "urlTest" -> {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release urlTest" }
                    if (fail) throw IllegalStateException("controlled URL-test failure")
                    731
                }
                "getState" -> State.Connected.ordinal
                "getProfileName" -> "Controlled UI test"
                "asBinder" -> Binder()
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Controlled ISagerNetService"
                else -> null
            }
        } as ISagerNetService
    }

    private fun startTest(scenario: ActivityScenario<MainActivity>, call: ControlledCall): Job {
        releases += call.release
        var job: Job? = null
        scenario.onActivity { activity ->
            activity.connection.service = call.service
            activity.stateChanged(State.Connected, null, null)
            activity.binding.stats.testConnection()
            val field = activity.binding.stats.javaClass.getDeclaredField("testJob").apply { isAccessible = true }
            job = field.get(activity.binding.stats) as Job
            assertEquals(activity.getString(R.string.connection_test_testing),
                activity.binding.stats.findViewById<TextView>(R.id.status).text.toString())
        }
        assertTrue("Controlled call entered", call.entered.await(10, TimeUnit.SECONDS))
        return requireNotNull(job)
    }

    private fun finishTest(call: ControlledCall, job: Job) {
        call.release.countDown()
        runBlocking { withTimeout(10_000) { job.join() } }
        instrumentation.waitForIdleSync()
    }

    @Test fun urlTestExceptionShowsFailureAndRestoresClickability() = withMain { scenario ->
        val call = ControlledCall(fail = true)
        val job = startTest(scenario, call)
        finishTest(call, job)
        scenario.onActivity {
            assertEquals(it.getString(R.string.connection_test_failed),
                it.binding.stats.findViewById<TextView>(R.id.status).text.toString())
            assertTrue(it.binding.stats.isEnabled)
        }
    }

    @Test fun disconnectAndSelectorInvalidateLateSuccessAndFailure() = withMain { scenario ->
        for (selector in listOf(false, true)) for (fail in listOf(false, true)) {
            val call = ControlledCall(fail)
            val job = startTest(scenario, call)
            scenario.onActivity {
                if (selector) it.cbSelectorUpdate(DataStore.selectedProxy + 1)
                else it.stateChanged(State.Stopped, null, null)
            }
            finishTest(call, job)
            scenario.onActivity {
                assertEquals(it.getString(if (selector) R.string.vpn_connected else R.string.not_connected),
                    it.binding.stats.findViewById<TextView>(R.id.status).text.toString())
                assertTrue(it.binding.stats.isEnabled)
            }
        }
    }
}
