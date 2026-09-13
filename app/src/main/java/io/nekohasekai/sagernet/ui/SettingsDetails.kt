package io.nekohasekai.sagernet.ui

import android.os.Build
import androidx.preference.*
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.bg.RunningServiceSnapshot

/** Reparent existing preference objects: keys, values, defaults and listeners are unchanged. */
internal class SettingsDetails(
    private val root: PreferenceScreen,
    private val manager: PreferenceManager,
    private val navigate: (PreferenceScreen) -> Unit,
) {
    private val context = root.context
    private val groups = linkedMapOf<PreferenceScreen, List<Preference>>()
    private fun pref(key: String) = checkNotNull(root.findPreference<Preference>(key))

    private fun group(parent: String, key: String, title: Int, keys: List<String>) {
        val rows = keys.map(::pref)
        val screen = manager.createPreferenceScreen(context).apply {
            this.key = key
            setTitle(title)
            layoutResource = R.layout.preference_settings_row
            isIconSpaceReserved = false
        }
        (pref(parent) as PreferenceGroup).addPreference(screen)
        screen.addPreference(Preference(context).apply {
            this.key = "${key}Back"
            setTitle(R.string.settings_back_main)
            layoutResource = R.layout.preference_settings_row
            order = -100
            setOnPreferenceClickListener { navigate(root); true }
        })
        rows.forEachIndexed { index, row ->
            row.parent?.removePreference(row)
            row.order = index
            screen.addPreference(row)
        }
        screen.addPreference(Preference(context).apply {
            this.key = "${screen.key}Timing"
            order = 100
            isSelectable = false
            layoutResource = R.layout.preference_settings_row
            setSummary(R.string.settings_next_connection)
        })
        groups[screen] = rows
    }

    fun organize() {
        group("uiConnectionRuntime", "uiConnectionDetails", R.string.settings_compatibility,
            listOf(Key.TUN_IMPLEMENTATION, Key.MTU, Key.ACQUIRE_WAKE_LOCK, Key.METERED_NETWORK))
        group("uiTrafficRouting", "uiRoutingDetails", R.string.settings_routing_details,
            listOf(Key.ENABLE_DNS_ROUTING, Key.RESOLVE_DESTINATION, Key.TRAFFIC_SNIFFING,
                Key.ENABLE_FAKEDNS, Key.BYPASS_LAN, Key.BYPASS_LAN_IN_CORE))
        group("uiDnsResolution", "uiDomainDetails", R.string.settings_domain_details,
            listOf("domain_strategy_for_remote", "domain_strategy_for_direct", "domain_strategy_for_server"))
        group("uiLocalProxy", "uiProxyDetails", R.string.ui_local_proxy,
            listOf(Key.SERVICE_MODE, Key.MIXED_PORT, Key.ALLOW_ACCESS, Key.APPEND_HTTP_PROXY))
        pref(Key.METERED_NETWORK).setTitle(R.string.settings_force_metered)
        pref(Key.RESOLVE_DESTINATION).setSummary(R.string.settings_resolve_help)
        pref(Key.ALLOW_INSECURE_ON_REQUEST).setSummary(R.string.settings_subscription_effect)
        listOf("domain_strategy_for_remote", "domain_strategy_for_direct", "domain_strategy_for_server").forEach { key ->
            (pref(key) as ListPreference).apply {
                entries = entryValues.map { if (it == "auto") context.getString(R.string.settings_follow_ipv6) else it }.toTypedArray()
                summaryProvider = Preference.SummaryProvider<ListPreference> {
                    if (it.value.isNullOrEmpty() || it.value == "auto") context.getString(R.string.settings_follow_ipv6)
                    else it.entry ?: it.value
                }
            }
        }
        refresh()
    }

    fun refresh() {
        val vpn = DataStore.serviceMode == Key.MODE_VPN
        listOf(Key.TUN_IMPLEMENTATION, Key.MTU, Key.BYPASS_LAN, Key.ENABLE_FAKEDNS).forEach {
            pref(it).isVisible = vpn
        }
        pref(Key.APPEND_HTTP_PROXY).isVisible = vpn && Build.VERSION.SDK_INT >= 29
        pref(Key.METERED_NETWORK).apply {
            isVisible = vpn
            isEnabled = Build.VERSION.SDK_INT >= 29
            setSummary(when {
                Build.VERSION.SDK_INT < 29 -> R.string.settings_metered_unsupported
                DataStore.meteredNetwork -> R.string.settings_metered_forced
                else -> R.string.settings_metered_system
            })
            val running = RunningServiceSnapshot.read(context)
            if (Build.VERSION.SDK_INT >= 29 && running?.serviceMode == Key.MODE_VPN &&
                running.metered != null && running.metered != DataStore.meteredNetwork) {
                summary = "$summary\n${context.getString(R.string.settings_metered_pending,
                    context.getString(if (running.metered) R.string.settings_metered_forced else R.string.settings_metered_system))}"
            }
        }
        groups.forEach { (screen, rows) ->
            screen.summary = if (screen.key == "uiDomainDetails") {
                rows.mapNotNull { row ->
                    val value = (row as ListPreference).value.orEmpty().ifEmpty { "auto" }
                    if (value == "auto") null else "${row.title}: $value"
                }.joinToString(" · ").ifEmpty { context.getString(R.string.settings_follow_ipv6) }
            } else {
                rows.joinToString(" · ") { row ->
                    val value = when (row) {
                        is TwoStatePreference -> context.getString(if (row.isChecked) R.string.on else R.string.off)
                        is ListPreference -> row.summary ?: row.entry ?: row.value.orEmpty()
                        is EditTextPreference -> row.text.orEmpty()
                        else -> row.summary ?: ""
                    }
                    "${row.title}: $value"
                } + if (!vpn && screen.key != "uiProxyDetails") "\n${context.getString(R.string.settings_retained_vpn)}" else ""
            }
        }
    }
}
