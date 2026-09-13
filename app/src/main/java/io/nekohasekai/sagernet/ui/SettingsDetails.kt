package io.nekohasekai.sagernet.ui

import android.os.Build
import androidx.preference.*
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.bg.RunningServiceSnapshot

/** Keep all applicable controls directly in their original top-level categories. */
internal class SettingsDetails(private val root: PreferenceScreen) {
    private val context = root.context
    private fun pref(key: String) = checkNotNull(root.findPreference<Preference>(key))

    fun organize() {
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
    }
}
