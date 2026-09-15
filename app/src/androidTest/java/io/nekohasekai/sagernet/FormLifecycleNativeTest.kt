package io.nekohasekai.sagernet

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.appcompat.widget.Toolbar
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
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import com.google.gson.JsonParser
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
    @get:Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule(requireRetainedHost = false)
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
        DataStore.configurationStore.putBoolean("isAutoConnect", false)
        // Enter through the exported launcher as a user would. Some physical-device ROMs
        // block ActivityScenario's first background launch even under instrumentation.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/io.nekohasekai.sagernet.ui.MainActivity"
        ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @After fun restoreDraft() {
        if (::cache.isInitialized) {
            TempDatabase.profileCacheDao.reset()
            cache.forEach { TempDatabase.profileCacheDao.put(it) }
            PublicDatabase.kvPairDao.reset()
            config.forEach { PublicDatabase.kvPairDao.put(it) }
        }
    }

    private fun <T : FragmentActivity> ActivityScenario<T>.useWithCleanup(block: (ActivityScenario<T>) -> Unit) {
        try { block(this) } finally {
            if (state != androidx.lifecycle.Lifecycle.State.DESTROYED) onActivity { it.finish() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (state != androidx.lifecycle.Lifecycle.State.DESTROYED && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
            check(state == androidx.lifecycle.Lifecycle.State.DESTROYED) { "Form Activity did not finish" }
            close()
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

    @Test fun realityTlsFieldsRemainVisibleAndEditableAfterRecreation() {
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "reality-form-review"))
        val profile = io.nekohasekai.sagernet.core.CoreClient.parseURI(
            "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?security=reality&sni=synthetic.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab12")
        val id = SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = groupId).putProfile(profile))
        try {
            val activityClass = io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity::class.java
            ActivityScenario.launch<io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity>(Intent(context, activityClass)
                .putExtra(io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity.EXTRA_PROFILE_ID, id)).useWithCleanup { scenario ->
                ready(scenario)
                repeat(2) { pass ->
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                            as io.nekohasekai.sagernet.ui.VialenPreferenceFragment
                        val security = fragment.findPreference<moe.matsuri.nb4a.ui.SimpleMenuPreference>("security")!!
                        assertEquals("tls", security.value)
                        assertTrue(security.entryValues.any { it.toString() == security.value })
                        for (key in listOf(Key.SERVER_SECURITY_CATEGORY, Key.SERVER_TLS_CAMOUFLAGE_CATEGORY)) {
                            assertTrue("Visible Reality category $key", fragment.findPreference<androidx.preference.Preference>(key)!!.isVisible)
                        }
                        assertEquals("ab12", fragment.findPreference<androidx.preference.EditTextPreference>("realityShortId")!!.text)
                        if (pass == 1) fragment.findPreference<androidx.preference.EditTextPreference>("sni")!!.text = "edited.example"
                    }
                    if (pass == 0) { scenario.recreate(); ready(scenario) }
                }
                lateinit var activity: io.nekohasekai.sagernet.ui.profile.StandardV2RaySettingsActivity
                scenario.onActivity { activity = it }
                runBlocking { activity.saveAndExit() }
                val saved = SagerDatabase.proxyDao.getById(id)!!.requireProfile()
                assertEquals("edited.example", saved.tls!!.serverName)
                assertEquals(profile.tls!!.reality, saved.tls.reality)
            }
        } finally {
            SagerDatabase.proxyDao.deleteByGroup(groupId)
            SagerDatabase.groupDao.deleteById(groupId)
        }
    }

    @Test fun groupCleanRecreationThenEditPersistsToRoom() {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup().apply { name = "forms-before" })
        try {
            ActivityScenario.launch<GroupSettingsActivity>(Intent(context, GroupSettingsActivity::class.java)
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id)).useWithCleanup { scenario ->
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
                .putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, id)).useWithCleanup { scenario ->
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
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id)).useWithCleanup { scenario ->
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
        DataStore.serverConfig = "{}"
        ActivityScenario.launch<ConfigEditActivity>(Intent(context, ConfigEditActivity::class.java)
            .putExtra("key", Key.SERVER_CONFIG)).useWithCleanup { scenario ->
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
                assertEquals("{}", DataStore.serverConfig)
            }
        }
    }

    @Test fun preferenceDialogKeepsVetoedInputAcrossRecreationThenCommitsOnce() {
        ActivityScenario.launch<SocksSettingsActivity>(Intent(context, SocksSettingsActivity::class.java)).useWithCleanup { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.VialenPreferenceFragment
                val preference = fragment.findPreference<androidx.preference.EditTextPreference>("serverAddress")!!
                fragment.onDisplayPreferenceDialog(preference)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.VialenPreferenceFragment
                val preference = fragment.findPreference<androidx.preference.EditTextPreference>("serverAddress")!!
                val before = preference.text
                preference.setOnPreferenceChangeListener { _, _ -> false }
                val dialog = (activity.supportFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG")
                    as androidx.fragment.app.DialogFragment).dialog as AlertDialog
                dialog.findViewById<EditText>(android.R.id.edit)!!.setText("pending.example")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(dialog.isShowing)
                assertEquals(before, preference.text)
                assertNotNull(dialog.findViewById<EditText>(android.R.id.edit)!!.error)
            }
            scenario.recreate()
            ready(scenario)
            var calls = 0
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.VialenPreferenceFragment
                val preference = fragment.findPreference<androidx.preference.EditTextPreference>("serverAddress")!!
                preference.setOnPreferenceChangeListener { _, _ -> calls++; true }
                val dialog = (activity.supportFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG")
                    as androidx.fragment.app.DialogFragment).dialog as AlertDialog
                assertEquals("pending.example", dialog.findViewById<EditText>(android.R.id.edit)!!.text.toString())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals("pending.example", preference.text)
                assertFalse(dialog.isShowing)
            }
            instrumentation.waitForIdleSync()
            assertEquals(1, calls)
            // Dismiss the owned activity before ActivityScenario's helper activity takes focus.
            scenario.onActivity { it.finish() }
        }
    }

    @Test fun subscriptionDialogPreservesItsValidationLayout() {
        ActivityScenario.launch<GroupSettingsActivity>(Intent(context, GroupSettingsActivity::class.java)
            .putExtra("newSubscription", true)).useWithCleanup { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.VialenPreferenceFragment
                val preference = fragment.findPreference<androidx.preference.EditTextPreference>("subscriptionLink")!!
                assertEquals(R.layout.layout_urltest_preference_dialog, preference.dialogLayoutResource)
                fragment.onDisplayPreferenceDialog(preference)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val dialog = (activity.supportFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG")
                    as androidx.fragment.app.DialogFragment).dialog as AlertDialog
                val input = dialog.findViewById<EditText>(android.R.id.edit)!!
                val container = dialog.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.input_layout)!!
                input.setText("not a URL")
                assertNotNull(container.error)
                input.setText("https://example.invalid/subscription")
                assertFalse(container.isErrorEnabled)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals("https://example.invalid/subscription", DataStore.subscriptionLink)
                activity.finish()
            }
        }
    }

    @Test fun unsavedDialogSaveCommitsAfterDialogDetaches() {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup().apply { name = "dialog-before" })
        try {
            ActivityScenario.launch<GroupSettingsActivity>(Intent(context, GroupSettingsActivity::class.java)
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, id)).useWithCleanup { scenario ->
                ready(scenario)
                scenario.onActivity { activity ->
                    DataStore.groupName = "dialog-after"
                    DataStore.dirty = true
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentByTag("form.unsaved")
                        as androidx.fragment.app.DialogFragment
                    (fragment.dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (SagerDatabase.groupDao.getById(id)?.name != "dialog-after" && System.nanoTime() < deadline) {
                    Thread.sleep(25)
                }
                assertEquals("dialog-after", SagerDatabase.groupDao.getById(id)?.name)
            }
        } finally { SagerDatabase.groupDao.deleteById(id) }
    }

    @Test fun lastNodeDeleteUndoAndCommitRefreshConnectionControls() {
        Assume.assumeTrue("This empty-home scenario requires no saved nodes", SagerDatabase.proxyDao.getAll().isEmpty())
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "UI availability fixture", userOrder = 99999))
        val bean = io.nekohasekai.sagernet.core.Profile(name = "UI availability node", type = "socks", server = "127.0.0.1",
            port = 9, socks = io.nekohasekai.sagernet.core.Profile.Socks())
        val entity = ProxyEntity(groupId = groupId).putProfile(bean)
        val id = SagerDatabase.proxyDao.addProxy(entity)
        DataStore.selectedGroup = groupId
        DataStore.selectedProxy = id
        try {
            ActivityScenario.launch<io.nekohasekai.sagernet.ui.MainActivity>(Intent(context,
                io.nekohasekai.sagernet.ui.MainActivity::class.java)).useWithCleanup { scenario ->
                fun awaitUi(check: (io.nekohasekai.sagernet.ui.MainActivity) -> Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var matched = false
                    while (!matched && System.nanoTime() < deadline) {
                        scenario.onActivity { matched = check(it) }
                        if (!matched) Thread.sleep(25)
                    }
                    assertTrue("Expected node/connection UI state", matched)
                }
                awaitUi { it.findViewById<View>(R.id.remove)?.isShown == true && it.binding.fab.isShown }
                scenario.onActivity { activity ->
                    val page = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                        as io.nekohasekai.sagernet.ui.ConfigurationFragment
                    val group = page.getCurrentGroupFragment()!!
                    val holder = group.configurationListView.findViewHolderForAdapterPosition(0)
                        as io.nekohasekai.sagernet.ui.ConfigurationFragment.GroupFragment.ConfigurationHolder
                    val sentinel = "content must not rebind on traffic"
                    holder.profileName.text = sentinel
                    kotlinx.coroutines.runBlocking {
                        group.adapter!!.onTrafficUpdated(listOf(io.nekohasekai.sagernet.aidl.TrafficData(id, 4096, 8192)))
                    }
                    assertEquals(sentinel, holder.profileName.text.toString())
                    assertTrue(holder.profileStatus.text.isNotEmpty())
                    kotlinx.coroutines.runBlocking {
                        group.adapter!!.onTrafficUpdated(listOf(io.nekohasekai.sagernet.aidl.TrafficData(id, 0, 0)))
                    }
                    assertEquals(sentinel, holder.profileName.text.toString())
                    assertEquals("", holder.profileStatus.text.toString())
                }
                scenario.onActivity { it.findViewById<View>(R.id.remove).performClick() }
                awaitUi { it.findViewById<View>(R.id.empty_state)?.isShown == true && !it.binding.fab.isShown }
                // The row is still persisted while Undo is offered, but must not be connectable.
                assertNotNull(SagerDatabase.proxyDao.getById(id))
                scenario.onActivity {
                    assertTrue(it.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick())
                }
                awaitUi { it.findViewById<View>(R.id.remove)?.isShown == true && it.binding.fab.isShown }
                scenario.onActivity {
                    it.findViewById<View>(R.id.remove).performClick()
                    val page = it.supportFragmentManager.findFragmentById(R.id.fragment_holder)
                        as io.nekohasekai.sagernet.ui.ConfigurationFragment
                    page.getCurrentGroupFragment()!!.undoManager.flush()
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (SagerDatabase.proxyDao.getById(id) != null && System.nanoTime() < deadline) Thread.sleep(25)
                assertNull(SagerDatabase.proxyDao.getById(id))
                awaitUi { !it.binding.fab.isShown }
            }
        } finally {
            SagerDatabase.proxyDao.deleteById(id)
            SagerDatabase.groupDao.deleteById(groupId)
        }
    }

    @Test fun numericDialogPreservesInvalidTextAndCommitsOnlyValidInput() {
        ActivityScenario.launch<ConfigEditActivity>(Intent(context, ConfigEditActivity::class.java)).useWithCleanup { scenario ->
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

    private fun editRawJson(scenario: ActivityScenario<ConfigSettingActivity>, text: String) {
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
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
                    as io.nekohasekai.sagernet.ui.VialenPreferenceFragment
                val preference = requireNotNull(fragment.findPreference<moe.matsuri.nb4a.ui.EditConfigPreference>(Key.SERVER_CONFIG))
                assertTrue(preference.isVisible)
                preference.performClick()
            }
            assertTrue("Raw configuration preference launches editor", resumed.await(10, TimeUnit.SECONDS))
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

    @Test fun ordinaryProfileDoesNotExposeCustomOverlayMenus() {
        ActivityScenario.launch<SocksSettingsActivity>(Intent(context, SocksSettingsActivity::class.java)).useWithCleanup { scenario ->
            ready(scenario)
            repeat(2) { pass ->
                if (pass == 1) { scenario.recreate(); ready(scenario) }
                scenario.onActivity { activity ->
                    val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
                    for (id in listOf(R.id.action_custom_config_json, R.id.action_custom_outbound_json)) {
                        assertFalse("Ordinary profiles must not expose overlay editing", menu.findItem(id)?.isVisible == true)
                    }
                }
            }
        }
    }

    @Test fun rawConfigurationSurvivesRecreationAndPersistsCompleteDocument() {
        val id = SagerDatabase.groupDao.createGroup(ProxyGroup().apply { name = "forms-json" })
        val raw = """{"outbounds":[{"type":"socks","tag":"fixture","server":"127.0.0.1","server_port":1080,"username":"synthetic","password":"synthetic"}],"route":{"final":"fixture"},"experimental":{"cache_file":{"enabled":false}}}"""
        try {
            ActivityScenario.launch<ConfigSettingActivity>(Intent(context, ConfigSettingActivity::class.java)).useWithCleanup { scenario ->
                ready(scenario)
                scenario.onActivity {
                    DataStore.editingGroup = id
                    DataStore.profileName = "forms-raw"
                }
                editRawJson(scenario, raw)
                scenario.recreate(); ready(scenario)
                lateinit var activity: ConfigSettingActivity
                scenario.onActivity {
                    activity = it
                    assertEquals(JsonParser.parseString(raw), JsonParser.parseString(DataStore.serverConfig))
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
                val row = SagerDatabase.proxyDao.getByGroup(id).single()
                assertEquals(ProxyEntity.TYPE_CONFIG, row.type)
                val saved = ProfileDocument.decode(row.document)
                assertEquals("raw_config", saved.kind)
                assertEquals("config", saved.scope)
                assertEquals("forms-raw", saved.name)
                assertNull(saved.profile)
                assertEquals(JsonParser.parseString(raw), JsonParser.parseString(saved.content))
                // Re-read the Room row rather than relying on the form's Bean projection.
                assertEquals(saved, ProfileDocument.decode(SagerDatabase.proxyDao.getById(row.id)!!.document))
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
            DataStore.serverConfig = large
            FormDraftState.save(saved, token)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(saved)
                assertTrue("Only a file token belongs in saved state", parcel.dataSize() < 4096)
            } finally { parcel.recycle() }
            FormDraftState.begin()
            TempDatabase.profileCacheDao.reset()
            FormDraftState.restore(saved)
            assertEquals(large, DataStore.serverConfig)
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
