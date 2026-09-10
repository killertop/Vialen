package io.nekohasekai.sagernet

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.appcompat.widget.Toolbar
import org.json.JSONObject
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import moe.matsuri.nb4a.ui.MTUPreference
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import io.nekohasekai.sagernet.ui.form.FormDraftState
import io.nekohasekai.sagernet.ui.form.showIntegerFormDialog
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.TempDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run only on an attached physical device; uses the production Activities and Room DAO. */
@RunWith(AndroidJUnit4::class)
class FormLifecycleNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var cache: List<KeyValuePair>
    private lateinit var config: List<KeyValuePair>

    @Before fun preserveDraft() {
        Assume.assumeTrue("Opt in on a physical device with -e vialenForms true",
            InstrumentationRegistry.getArguments().getString("vialenForms") == "true")
        check(DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Stopped ||
            DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Idle)
        config = PublicDatabase.kvPairDao.all().map { row -> KeyValuePair(row.key).also {
            it.valueType = row.valueType; it.value = row.value.copyOf()
        } }
        cache = TempDatabase.profileCacheDao.all().map { row -> KeyValuePair(row.key).also {
            it.valueType = row.valueType; it.value = row.value.copyOf()
        } }
    }
    @After fun restoreDraft() {
        if (::cache.isInitialized) {
            TempDatabase.profileCacheDao.reset()
            cache.forEach { TempDatabase.profileCacheDao.put(it) }
            PublicDatabase.kvPairDao.reset()
            config.forEach { PublicDatabase.kvPairDao.put(it) }
        }
    }

    private fun <T : FragmentActivity> ready(scenario: ActivityScenario<T>) {
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            if (activity.supportFragmentManager.findFragmentById(R.id.settings)?.view != null) {
                latch.countDown()
            } else activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment,
                        v: View, savedInstanceState: Bundle?) {
                        fm.unregisterFragmentLifecycleCallbacks(this)
                        latch.countDown()
                    }
                }, false)
        }
        assertTrue("Form initialization completed", latch.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    @Test fun groupCleanRecreationThenEditPersistsToRoom() {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup().apply { name = "forms-before" })
        try {
            ActivityScenario.launch<GroupSettingsActivity>(Intent(context, GroupSettingsActivity::class.java)
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id)).use { scenario ->
                ready(scenario)
                scenario.recreate()
                ready(scenario)
                scenario.onActivity {
                    DataStore.groupName = "forms-after"
                    assertTrue(DataStore.dirty)
                    it.onBackPressedDispatcher.onBackPressed()
                    it.onSupportNavigateUp()
                    assertFalse(it.isFinishing)
                    assertEquals(1, it.supportFragmentManager.fragments.count { f ->
                        f is GroupSettingsActivity.UnsavedChangesDialogFragment
                    })
                    (it.supportFragmentManager.findFragmentByTag("form.unsaved") as androidx.fragment.app.DialogFragment)
                        .dismissNow()
                }
                lateinit var activity: GroupSettingsActivity
                scenario.onActivity { activity = it }
                runBlocking { activity.saveAndExit() }
                assertEquals("forms-after", SagerDatabase.groupDao.getById(id)!!.name)
            }
        } finally { SagerDatabase.groupDao.deleteById(id) }
    }

    @Test fun ruleDirtyRecreationAndToolbarUseUnsavedDecision() {
        val id = SagerDatabase.rulesDao.createRule(RuleEntity().apply { name = "forms-before"; domains = "example.com" })
        try {
            ActivityScenario.launch<RouteSettingsActivity>(Intent(context, RouteSettingsActivity::class.java)
                .putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, id)).use { scenario ->
                ready(scenario)
                scenario.onActivity { DataStore.routeName = "forms-after" }
                scenario.recreate(); ready(scenario)
                scenario.onActivity {
                    assertTrue(DataStore.dirty)
                    it.onBackPressedDispatcher.onBackPressed()
                    it.onSupportNavigateUp()
                    it.onBackPressedDispatcher.onBackPressed()
                    assertFalse(it.isFinishing)
                    assertEquals(1, it.supportFragmentManager.fragments.count { f ->
                        f is RouteSettingsActivity.UnsavedChangesDialogFragment
                    })
                }
                lateinit var activity: RouteSettingsActivity
                scenario.onActivity { activity = it }
                runBlocking { activity.saveAndExit() }
                assertEquals("forms-after", SagerDatabase.rulesDao.getById(id)!!.name)
            }
        } finally { SagerDatabase.rulesDao.deleteById(id) }
    }

    @Test fun missingGroupSaveReportsFailureWithoutExiting() {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup().apply { name = "forms-deleted" })
        try {
            ActivityScenario.launch<GroupSettingsActivity>(Intent(context, GroupSettingsActivity::class.java)
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id)).use { scenario ->
                ready(scenario)
                lateinit var activity: GroupSettingsActivity
                scenario.onActivity { activity = it; DataStore.groupName = "unsaved" }
                SagerDatabase.groupDao.deleteById(id)
                runBlocking { activity.saveAndExit() }
                scenario.onActivity { assertFalse(it.isFinishing); assertTrue(DataStore.dirty) }
                assertNull(SagerDatabase.groupDao.getById(id))
            }
        } finally { SagerDatabase.groupDao.deleteById(id) }
    }

    @Test fun configDraftRecreatesWithoutSavingAndInvalidJsonStaysOpen() {
        DataStore.serverCustom = "{}"
        ActivityScenario.launch<ConfigEditActivity>(Intent(context, ConfigEditActivity::class.java)
            .putExtra("key", Key.SERVER_CUSTOM)).use { scenario ->
            scenario.onActivity { it.binding.editor.setTextContent("{invalid") }
            scenario.recreate()
            scenario.onActivity {
                assertEquals("{invalid", it.binding.editor.text.toString())
                assertTrue(it.dirty)
                it.onBackPressedDispatcher.onBackPressed()
                it.onSupportNavigateUp()
                assertEquals(1, it.supportFragmentManager.fragments.count { f ->
                    f is ConfigEditActivity.UnsavedChangesDialogFragment
                })
                (it.supportFragmentManager.findFragmentByTag("form.unsaved") as androidx.fragment.app.DialogFragment)
                    .dismissNow()
                it.saveAndExit()
                assertFalse(it.isFinishing)
                assertEquals("{}", DataStore.serverCustom)
            }
        }
    }

    @Test fun numericDialogPreservesInvalidTextAndCommitsOnlyValidInput() {
        ActivityScenario.launch<ConfigEditActivity>(Intent(context, ConfigEditActivity::class.java)).use { scenario ->
            lateinit var dialog: AlertDialog
            var commits = 0
            scenario.onActivity { activity ->
                dialog = activity.showIntegerFormDialog("MTU", "1500", 1000..10000) { commits++; true }
            }
            // Dialog dispatches OnShow asynchronously; let it install the validation listener.
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                fun findInput(view: View): EditText? = when (view) {
                    is EditText -> view
                    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findInput(view.getChildAt(it)) }
                    else -> null
                }
                val input = requireNotNull(findInput(dialog.window!!.decorView))
                for (bad in listOf("", "0", "999", "10001", "2147483648")) {
                    input.setText(bad)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(dialog.isShowing)
                    assertEquals(bad, input.text.toString())
                    assertNotNull(input.error)
                    assertEquals(0, commits)
                }
                input.setText("1500")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(1, commits)
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test fun mtuCustomCommitRunsChangeListenerOnceAndHonorsVeto() {
        ActivityScenario.launch<ConfigEditActivity>(Intent(context, ConfigEditActivity::class.java)).use { scenario ->
            lateinit var dialog: AlertDialog
            var calls = 0
            var accept = false
            scenario.onActivity { activity ->
                val preference = MTUPreference(activity).apply { value = "1500" }
                preference.setOnPreferenceChangeListener { _, proposed ->
                    calls++
                    assertEquals("1500", proposed)
                    accept
                }
                dialog = preference.showCustomDialog()
            }
            // Wait for OnShow before simulating the user's positive-button click.
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(1, calls)
                assertTrue(dialog.isShowing)
                accept = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(2, calls)
                assertFalse(dialog.isShowing)
            }
        }
    }

    private fun editCustomJson(scenario: ActivityScenario<SocksSettingsActivity>, menuId: Int, text: String) {
        val resumed = CountDownLatch(1)
        var editor: ConfigEditActivity? = null
        val application = context.applicationContext as Application
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is ConfigEditActivity) { editor = activity; resumed.countDown() }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(observer)
        try {
            scenario.onActivity { activity ->
                val item = activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(menuId)
                assertNotNull(item)
                assertTrue(activity.onOptionsItemSelected(item))
            }
            assertTrue("New profile JSON action launches editor", resumed.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                requireNotNull(editor).apply { binding.editor.setTextContent(text); saveAndExit() }
            }
            instrumentation.waitForIdleSync()
        } finally {
            application.unregisterActivityLifecycleCallbacks(observer)
            // An assertion must not leave a launched child above the parent during close().
            instrumentation.runOnMainSync { editor?.takeUnless { it.isFinishing }?.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun newProfileCustomJsonSurvivesRecreationAndPersists() {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup().apply { name = "forms-json" })
        try {
            ActivityScenario.launch<SocksSettingsActivity>(Intent(context, SocksSettingsActivity::class.java)).use { scenario ->
                ready(scenario)
                scenario.onActivity {
                    DataStore.editingGroup = id
                    DataStore.serverAddress = "127.0.0.1"
                    DataStore.serverPort = 1080
                }
                editCustomJson(scenario, R.id.action_custom_config_json, "{\"test_config\":true}")
                editCustomJson(scenario, R.id.action_custom_outbound_json, "{\"test_outbound\":true}")
                scenario.recreate(); ready(scenario)
                lateinit var activity: SocksSettingsActivity
                scenario.onActivity {
                    activity = it
                    assertTrue(DataStore.dirty)
                    it.onBackPressedDispatcher.onBackPressed()
                    it.onSupportNavigateUp()
                    assertFalse(it.isFinishing)
                    assertEquals(1, it.supportFragmentManager.fragments.count { f ->
                        f is io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity.UnsavedChangesDialogFragment
                    })
                    (it.supportFragmentManager.findFragmentByTag("form.unsaved") as androidx.fragment.app.DialogFragment)
                        .dismissNow()
                }
                runBlocking { activity.saveAndExit() }
                val saved = SagerDatabase.proxyDao.getByGroup(id).single().requireBean()
                assertTrue(JSONObject(saved.customConfigJson).getBoolean("test_config"))
                assertTrue(JSONObject(saved.customOutboundJson).getBoolean("test_outbound"))
            }
        } finally {
            SagerDatabase.proxyDao.deleteByGroup(id)
            SagerDatabase.groupDao.deleteById(id)
        }
    }

    @Test fun largeJsonDraftDoesNotEnterBinderSavedState() {
        val saved = Bundle()
        val token = FormDraftState.begin()
        val large = "{\"payload\":\"" + "x".repeat(1_200_000) + "\"}"
        try {
            DataStore.serverCustom = large
            FormDraftState.save(saved, token)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(saved)
                assertTrue("Only a file token belongs in saved state", parcel.dataSize() < 4096)
            } finally { parcel.recycle() }
            FormDraftState.begin()
            TempDatabase.profileCacheDao.reset()
            FormDraftState.restore(saved)
            assertEquals(large, DataStore.serverCustom)
        } finally { FormDraftState.discard(token) }
    }

    @Test fun draftSnapshotRestoresAfterInMemorySessionLoss() {
        val saved = Bundle()
        val token = FormDraftState.begin()
        DataStore.routeName = "process-draft"
        DataStore.dirty = true
        FormDraftState.save(saved, token)
        FormDraftState.begin() // Simulates a fresh process token; cache is also lost there.
        TempDatabase.profileCacheDao.reset()
        FormDraftState.restore(saved)
        assertEquals("process-draft", DataStore.routeName)
        assertTrue(DataStore.dirty)
        FormDraftState.discard(token)
    }
}
