package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.RuntimeDiagnostics
import moe.matsuri.nb4a.ui.*

class SettingsPreferenceFragment : io.nekohasekai.sagernet.ui.VialenPreferenceFragment() {

    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private var managedNotice: androidx.appcompat.app.AlertDialog? = null
    private fun diagnosticService() = (activity as? MainActivity)?.connection?.service
    private val refreshDiagnostics = object : Runnable {
        override fun run() {
            val remaining = RuntimeDiagnostics.remainingMillis(diagnosticService())
            findPreference<Preference>("uiDetailedDiagnostics")?.summary =
                if (remaining > 0) getString(R.string.runtime_diagnostics_active,
                    (remaining + 59_999) / 60_000)
                else getString(R.string.runtime_diagnostics_summary)
            diagnosticsHandler.postDelayed(this, 1_000)
        }
    }

    private lateinit var isProxyApps: SwitchPreference



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        // Section bands and dividers belong only to global settings, not protocol forms.
        setDivider(android.graphics.drawable.ColorDrawable(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.vialen_outline)))
        setDividerHeight(resources.displayMetrics.density.toInt().coerceAtLeast(1))
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)!!
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)!!

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)!!

        val mtu = findPreference<MTUPreference>(Key.MTU)!!

        findPreference<Preference>("uiDetailedDiagnostics")!!.setOnPreferenceClickListener {
            val enabled = RuntimeDiagnostics.remainingMillis(diagnosticService()) > 0
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.runtime_diagnostics_title)
                .setMessage(if (enabled) R.string.runtime_diagnostics_stop_message else R.string.runtime_diagnostics_start_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(if (enabled) R.string.runtime_diagnostics_stop else R.string.runtime_diagnostics_start) { _, _ ->
                    runCatching { RuntimeDiagnostics.setEnabled(diagnosticService(), !enabled) }
                        .onFailure { error ->
                            android.widget.Toast.makeText(requireContext(), error.readableMessage,
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    diagnosticsHandler.removeCallbacks(refreshDiagnostics)
                    refreshDiagnostics.run()
                }.show()
            true
        }
        findPreference<Preference>("uiManagedSettings")!!.setOnPreferenceClickListener {
            showManagedSettingsNotice()
            true
        }

        mixedPort.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }
        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            DataStore.dirty = true
            needReload()
            true
        }

        findPreference<Preference>("uiEditApps")!!.setOnPreferenceClickListener {
            startActivity(Intent(activity, AppManagerActivity::class.java))
            true
        }
        findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)!!.onPreferenceChangeListener = reloadListener

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)!!

        mixedPort.onPreferenceChangeListener = reloadListener
        appendHttpProxy.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        remoteDns.onPreferenceChangeListener = reloadListener
        directDns.onPreferenceChangeListener = reloadListener
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener
        allowAccess.onPreferenceChangeListener = reloadListener

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
    }

    override fun onResume() {
        super.onResume()

        diagnosticsHandler.removeCallbacks(refreshDiagnostics)
        refreshDiagnostics.run()
        if (!DataStore.configurationStore.getBoolean("managedRuntimeNoticeAcknowledged", false)) {
            showManagedSettingsNotice()
        }

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
    }

    override fun onPause() {
        diagnosticsHandler.removeCallbacks(refreshDiagnostics)
        super.onPause()
    }

    private fun showManagedSettingsNotice() {
        if (managedNotice?.isShowing == true) return
        managedNotice = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.runtime_managed_title)
            .setMessage(R.string.runtime_managed_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.configurationStore.putBoolean("managedRuntimeNoticeAcknowledged", true)
            }.show()
    }

    override fun onDestroyView() {
        diagnosticsHandler.removeCallbacks(refreshDiagnostics)
        managedNotice?.dismiss()
        managedNotice = null
        super.onDestroyView()
    }

}
