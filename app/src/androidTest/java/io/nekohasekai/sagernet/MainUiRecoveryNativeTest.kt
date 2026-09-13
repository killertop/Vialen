package io.nekohasekai.sagernet

import android.app.Activity
import android.app.Application
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
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.ProxyEntity
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
    @get:Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule(requireRetainedHost = false)

    companion object {
        @Volatile private var cleanupFailed = false
    }

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
        check(!cleanupFailed) { "Previous Main UI cleanup failed; externally force-stop and restore the isolated Debug target before retrying" }
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
            check(!cleanupFailed) { "Main UI cleanup failed; externally force-stop and restore the isolated Debug target; live snapshot restoration refused" }
            PublicDatabase.kvPairDao.reset(); config.forEach { PublicDatabase.kvPairDao.put(it) }
            TempDatabase.profileCacheDao.reset(); cache.forEach { TempDatabase.profileCacheDao.put(it) }
            DataStore.serviceState = oldState
        }
    }

    private fun withMain(block: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario = try {
            ActivityScenario.launch(MainActivity::class.java)
        } catch (error: Throwable) {
            // Launch may time out after creating an Activity without returning its owner.
            cleanupFailed = true
            println("MAIN_UI_LAUNCH recovery=external_force_stop_and_restore_isolated_debug")
            throw error
        }
        var failure: Throwable? = null
        try {
            isolate(scenario)
            block(scenario)
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Release blocking fake calls before lifecycle teardown, including assertion failures.
            releases.forEach { it.countDown() }
            var owner: MainActivity? = null
            val destroyed = CountDownLatch(1)
            val application = instrumentation.targetContext.applicationContext as Application
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === owner) destroyed.countDown()
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            }
            application.registerActivityLifecycleCallbacks(callbacks)
            try {
                scenario.onActivity {
                    owner = it
                    it.connection.disconnect(it)
                    it.stateChanged(State.Stopped, null, null)
                    println("MAIN_UI_TEARDOWN finishAndRemoveTask owner=${System.identityHashCode(it)}")
                    it.finishAndRemoveTask()
                }
                check(destroyed.await(10, TimeUnit.SECONDS)) { "MainActivity did not reach DESTROYED" }
                // The owner is already destroyed: close only unregisters ActivityScenario.
                scenario.close()
                println("MAIN_UI_TEARDOWN destroyed=true scenario_closed=true")
            } catch (error: Throwable) {
                cleanupFailed = true
                println("MAIN_UI_TEARDOWN recovery=external_force_stop_and_restore_isolated_debug")
                println("MAIN_UI_TEARDOWN failed=${error.javaClass.simpleName} destroyed=${destroyed.count == 0L}")
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } finally {
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
        }
        failure?.let { throw it }
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

    @Test fun connectionControlsStayOnHomeAcrossPageRecreation() {
        // Own a fixture: an empty installation intentionally has no connection controls.
        val group = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "Home controls fixture"))
        val profile = io.nekohasekai.sagernet.core.Profile(name = "", type = "socks", server = "127.0.0.1",
            port = 9, socks = io.nekohasekai.sagernet.core.Profile.Socks())
        val proxy = SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group).putProfile(profile))
        DataStore.selectedGroup = group
        DataStore.selectedProxy = proxy
        DataStore.configurationStore.putBoolean("managedRuntimeNoticeAcknowledged", true)
        try { withMain { scenario ->
            for ((page, name) in listOf(
                R.id.nav_settings to "SettingsFragment",
                R.id.nav_group to "GroupFragment",
                R.id.nav_route to "RouteFragment",
                R.id.nav_about to "AboutFragment",
                R.id.nav_configuration to "ConfigurationFragment",
            )) {
                val show = page == R.id.nav_configuration
                scenario.onActivity {
                    it.displayFragmentWithId(page)
                    it.supportFragmentManager.executePendingTransactions()
                }
                fun matches(activity: MainActivity) =
                    activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)?.javaClass?.simpleName == name &&
                        activity.binding.fab.visibility == if (show) View.VISIBLE else View.GONE
                awaitView(scenario, ::matches)
                scenario.recreate()
                isolate(scenario)
                awaitView(scenario, ::matches)
                scenario.onActivity {
                    assertEquals(show, it.binding.stats.allowShow)
                    if (page == R.id.nav_settings) {
                        val settings = it.supportFragmentManager.findFragmentById(R.id.settings)
                            as io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
                        val adapter = settings.listView.adapter as androidx.preference.PreferenceGroupAdapter
                        for (key in listOf("uiEditApps", "remoteDns", "uiProxyDetails",
                            "globalAllowInsecure", "uiConnectionDetails", "uiDomainDetails", "uiRoutingDetails")) {
                            assertTrue("Setting must be exposed without expansion: $key",
                                adapter.getPreferenceAdapterPosition(key) >= 0)
                        }
                        for (key in listOf("uiDetailedDiagnostics", "uiManagedSettings")) {
                            assertEquals("Removed setting must not return after recreation: $key",
                                -1, adapter.getPreferenceAdapterPosition(key))
                        }
                    }
                }
            }
        } } finally {
            SagerDatabase.proxyDao.deleteById(proxy)
            SagerDatabase.groupDao.deleteById(group)
        }
    }

    @Test fun settingsDetailsRetainOverridesAcrossModesAndRecreation() {
        DataStore.serviceMode = Key.MODE_VPN
        DataStore.meteredNetwork = true
        DataStore.mtu = 1000
        DataStore.enableFakeDns = true
        DataStore.appendHttpProxy = true
        DataStore.configurationStore.putString("domain_strategy_for_remote", "ipv4_only")
        DataStore.configurationStore.putString("domain_strategy_for_direct", "auto")
        DataStore.configurationStore.putString("domain_strategy_for_server", "prefer_ipv6")
        withMain { scenario ->
            scenario.onActivity { activity ->
                activity.displayFragmentWithId(R.id.nav_settings)
                activity.supportFragmentManager.executePendingTransactions()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val settings = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
                val root = settings.preferenceScreen
                assertNull(settings.findPreference<androidx.preference.Preference>("showDirectSpeed"))
                assertTrue(root.findPreference<androidx.preference.Preference>("uiDomainDetails")!!.summary.toString().contains("prefer_ipv6"))
                settings.onNavigateToScreen(root.findPreference("uiConnectionDetails")!!)
                val mtu = settings.findPreference<androidx.preference.ListPreference>(Key.MTU)!!
                assertTrue(mtu.summary.toString().contains("1280"))
                assertFalse(mtu.callChangeListener("1000"))
                assertTrue(mtu.callChangeListener("1280"))
                assertEquals(1000, DataStore.mtu) // validation never silently persists a repair
                assertTrue(settings.findPreference<androidx.preference.SwitchPreference>(Key.METERED_NETWORK)!!.isChecked)
                settings.onNavigateToScreen(root)
                settings.onNavigateToScreen(root.findPreference("uiProxyDetails")!!)
                val mode = settings.findPreference<androidx.preference.ListPreference>(Key.SERVICE_MODE)!!
                assertTrue(mode.callChangeListener(Key.MODE_PROXY))
                mode.value = Key.MODE_PROXY
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val settings = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
                assertFalse(settings.findPreference<androidx.preference.Preference>(Key.APPEND_HTTP_PROXY)!!.isVisible)
                assertTrue(DataStore.appendHttpProxy)
                assertTrue(DataStore.meteredNetwork)
                assertEquals(1000, DataStore.mtu)
                assertEquals("prefer_ipv6", DataStore.configurationStore.getString("domain_strategy_for_server"))
            }
            scenario.recreate()
            isolate(scenario)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val settings = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
                assertEquals("uiProxyDetails", settings.preferenceScreen.key)
                activity.onBackPressedDispatcher.onBackPressed()
                assertNotNull(settings.findPreference<androidx.preference.Preference>("uiDomainDetails"))
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
            assertEquals(activity.getString(R.string.ui_connectivity_testing),
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
                // Service state and connectivity-test state occupy separate lines.
                val connectivity = if (selector) it.getString(R.string.ui_connectivity_check_hint) else ""
                assertEquals(connectivity,
                    it.binding.stats.findViewById<TextView>(R.id.status).text.toString())
                val serviceStatus = if (!selector) R.string.not_connected else
                    if (DataStore.serviceMode == Key.MODE_VPN) R.string.ui_vpn_service_connected
                    else R.string.ui_proxy_service_connected
                assertEquals(it.getString(serviceStatus),
                    it.binding.stats.findViewById<TextView>(R.id.service_status).text.toString())
                assertTrue(it.binding.stats.isEnabled)
            }
        }
    }
}
