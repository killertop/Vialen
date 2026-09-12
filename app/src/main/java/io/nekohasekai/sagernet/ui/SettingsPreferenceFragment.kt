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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.AppRoutingStore
import io.nekohasekai.sagernet.utils.InstalledAppAccess
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.RuntimeDiagnostics
import moe.matsuri.nb4a.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var appRoutingSummaryJob: Job? = null



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        // Preserve the existing divider offsets and all preference hit areas. Draw the
        // grouped pearl surfaces below rows instead of wrapping/reparenting preferences.
        setDivider(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        setDividerHeight(resources.displayMetrics.density.toInt().coerceAtLeast(1))
        listView.addItemDecoration(io.nekohasekai.sagernet.widget.PreferenceSurfaceDecoration(requireContext()))
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

        appRoutingSummaryJob?.cancel()
        appRoutingSummaryJob = lifecycleScope.launch {
            val context = requireContext().applicationContext
            val config = AppRoutingStore.read()
            val valid = withContext(Dispatchers.IO) {
                !config.enabled || config.validate(InstalledAppAccess.read(context).packages?.keys,
                    context.packageName) == null
            }
            findPreference<Preference>("uiEditApps")?.summary = when {
                !config.enabled -> getString(R.string.app_routing_off_summary)
                !valid -> getString(R.string.app_routing_needs_attention)
                else -> getString(if (config.bypass) R.string.app_routing_bypass_summary
                    else R.string.app_routing_proxy_summary, config.packages.size)
            }
        }
    }

    override fun onPause() {
        appRoutingSummaryJob?.cancel()
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
