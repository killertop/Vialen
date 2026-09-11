package io.nekohasekai.sagernet.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteRuleSet
import java.io.File

internal class RouteRuleSetDialogs(private val owner: RouteSettingsActivity, private val direction: String, private val changed: () -> Unit) {
    private fun refs() = RouteRuleSet.decode(DataStore.routeRuleSets)
    private fun save(refs: List<RouteRuleSet>) {
        DataStore.routeRuleSets = RouteRuleSet.encode(refs)
        changed()
    }
    fun show() {
        val selected = refs().filter { it.match == direction }
        MaterialAlertDialogBuilder(owner).setTitle(R.string.route_set_files)
            .setItems((selected.map { it.name } + owner.getString(R.string.route_set_add)).toTypedArray()) { _, index ->
                if (index == selected.size) add()
                else MaterialAlertDialogBuilder(owner).setTitle(R.string.route_set_remove)
                    .setMessage(selected[index].source)
                    .setPositiveButton(R.string.delete) { _, _ -> save(refs().filter { it != selected[index] }) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun add() {
        val presets = mutableListOf(RouteRuleSet.official("geoip", "cn", owner.getString(R.string.route_set_cn_ip), direction))
        if (direction != "source") {
            presets += RouteRuleSet.official("geosite", "cn", owner.getString(R.string.route_set_cn_domain), direction)
            presets += RouteRuleSet.official("geosite", "category-ads-all", owner.getString(R.string.route_set_ads), direction)
        }
        MaterialAlertDialogBuilder(owner).setTitle(R.string.route_set_add)
            .setItems((presets.map { it.name } + listOf(owner.getString(R.string.route_set_custom), owner.getString(R.string.route_set_local))).toTypedArray()) { _, index ->
                when (index) {
                    presets.size -> custom()
                    presets.size + 1 -> local()
                    else -> save(refs() + presets[index])
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun custom() {
        val manager = owner.supportFragmentManager
        if (!manager.isStateSaved && manager.findFragmentByTag("route-rule-set-editor") == null) {
            RouteRuleSetEditor().apply { arguments = android.os.Bundle().apply { putString("direction", direction) } }
                .show(manager, "route-rule-set-editor")
        }
    }
    private fun local() {
        val files = File(owner.filesDir, "rule-sets").listFiles()?.filter { it.isFile && it.extension in setOf("srs", "json") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) { MaterialAlertDialogBuilder(owner).setMessage(R.string.route_set_empty_local).setPositiveButton(android.R.string.ok, null).show(); return }
        MaterialAlertDialogBuilder(owner).setTitle(R.string.route_set_local).setItems(files.map { it.name }.toTypedArray()) { _, index ->
            val f = files[index]
            save(refs() + RouteRuleSet(f.name, "rule-sets/" + f.name, if (f.extension == "srs") "binary" else "source", direction))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
