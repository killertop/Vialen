package io.nekohasekai.sagernet.ui

import android.view.ContextThemeWrapper
import androidx.preference.*
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.bg.RunningServiceSnapshot
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import moe.matsuri.nb4a.TempDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsDetailsTest {
    private var mode = Key.MODE_VPN
    private class Store : PreferenceDataStore() {
        val values = mutableMapOf<String, Any>("meteredNetwork" to true, "mtu" to "1000",
            "appendHttpProxy" to true, "domain_strategy_for_remote" to "ipv4_only",
            "domain_strategy_for_server" to "prefer_ipv6")
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun putString(key: String, value: String?) { if (value != null) values[key] = value }
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    }
    @Before fun setup() {
        val dao = mockk<KeyValuePair.Dao>(relaxed = true)
        every { dao[any()] } returns null
        mockkObject(PublicDatabase.Companion, TempDatabase.Companion)
        every { PublicDatabase.kvPairDao } returns dao
        every { TempDatabase.profileCacheDao } returns dao
        mockkObject(DataStore)
        every { DataStore.serviceMode } answers { mode }
        every { DataStore.meteredNetwork } returns true
        every { DataStore.baseService } returns null
    }
    @After fun cleanup() { unmockkAll() }

    @Test fun meteredSummaryDistinguishesRunningSnapshotFromSavedOverride() {
        mockkObject(RunningServiceSnapshot.Companion)
        every { RunningServiceSnapshot.read(any()) } returns RunningServiceSnapshot(Key.MODE_VPN, 2080, false)
        val (root, details, store) = settings()
        val before = store.values.toMap()
        val row = root.findPreference<Preference>(Key.METERED_NETWORK)!!
        val pending = row.summary.toString()
        every { RunningServiceSnapshot.read(any()) } returns RunningServiceSnapshot(Key.MODE_VPN, 2080, true)
        details.refresh()
        assertNotEquals(pending, row.summary.toString())
        assertTrue(pending.startsWith(row.summary.toString()))
        every { RunningServiceSnapshot.read(any()) } returns null
        details.refresh()
        assertFalse(row.summary.toString().contains('\n'))
        assertEquals(before, store.values)
    }

    private fun settings(): Triple<PreferenceScreen, SettingsDetails, Store> {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<android.content.Context>(), R.style.Theme_SagerNet)
        val store = Store()
        val manager = PreferenceManager(context).apply { preferenceDataStore = store }
        val root = manager.inflateFromResource(context, R.xml.global_preferences, null)
        val details = SettingsDetails(root)
        details.organize()
        return Triple(root, details, store)
    }

    @Test fun movedPreferencesRetainOverridesAndRemainDiscoverable() {
        val (root, details, store) = settings()
        val before = store.values.toMap()
        assertNull(root.findPreference<Preference>("uiConnectionDetails"))
        assertNull(root.findPreference<Preference>("uiRoutingDetails"))
        assertNull(root.findPreference<Preference>("showDirectSpeed"))
        assertEquals("uiConnectionRuntime", root.findPreference<Preference>("mtu")!!.parent!!.key)
        assertEquals("uiDnsResolution", root.findPreference<Preference>("domain_strategy_for_remote")!!.parent!!.key)
        assertTrue(root.findPreference<Preference>("domain_strategy_for_remote")!!.summary.toString().contains("ipv4_only"))
        assertTrue(root.findPreference<Preference>("domain_strategy_for_server")!!.summary.toString().contains("prefer_ipv6"))
        assertTrue(root.findPreference<Preference>("mtu")!!.summary.toString().contains("1280"))
        mode = Key.MODE_PROXY
        details.refresh()
        assertFalse(root.findPreference<Preference>("mtu")!!.isVisible)
        assertFalse(root.findPreference<Preference>("appendHttpProxy")!!.isVisible)
        assertFalse(root.findPreference<Preference>("enableFakeDns")!!.isVisible)
        assertEquals(before, store.values)
        mode = Key.MODE_VPN
        details.refresh()
        assertTrue(root.findPreference<SwitchPreference>("meteredNetwork")!!.isChecked)
        assertTrue(root.findPreference<Preference>("mtu")!!.isVisible)
        assertEquals(before, store.values)
    }

    @Test @Config(sdk = [28]) fun unsupportedMeteredOverrideIsVisibleButNotAppliedOrErased() {
        val (root, _, store) = settings()
        val metered = root.findPreference<SwitchPreference>("meteredNetwork")!!
        assertTrue(metered.isVisible)
        assertFalse(metered.isEnabled)
        assertTrue(metered.isChecked)
        assertTrue(metered.summary.toString().contains("Android 10"))
        assertEquals(true, store.values["meteredNetwork"])
    }
}
