package io.nekohasekai.sagernet.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.AppRoutingStore
import io.nekohasekai.sagernet.databinding.LayoutAppsBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.AppRoutingConfig
import io.nekohasekai.sagernet.utils.InstalledAppAccess
import io.nekohasekai.sagernet.utils.ProxyAppRecommendations
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppManagerActivity : ThemedActivity() {
    private data class ProxiedApp(val info: ApplicationInfo, val name: String) {
        val packageName get() = info.packageName
        val uid get() = info.uid
        val system get() = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    private lateinit var binding: LayoutAppsBinding
    private lateinit var original: AppRoutingConfig
    private lateinit var draft: AppRoutingConfig
    private var apps = emptyList<ProxiedApp>()
    private var visibleApps = emptyList<ProxiedApp>()
    private var snapshot = InstalledAppAccess.Snapshot()
    private var loader: Job? = null
    private var loading = false
    private var saving = false
    private var recommending = false
    private var sysApps = true
    private val ready get() = !loading && snapshot.packages != null
    private fun isSelected(item: ProxiedApp) = apps.any { it.uid == item.uid && it.packageName in draft.packages }

    private inner class AppViewHolder(val row: LayoutAppsItemBinding) : RecyclerView.ViewHolder(row.root) {
        fun bind(item: ProxiedApp) {
            row.itemicon.setImageDrawable(item.info.loadIcon(packageManager))
            row.title.text = item.name
            row.desc.text = "${item.packageName} (${item.uid})"
            row.itemcheck.isChecked = isSelected(item)
            row.root.setOnClickListener {
                if (!ready || saving) return@setOnClickListener
                val sharedUid = apps.filter { it.uid == item.uid }.map { it.packageName }.toSet()
                draft = draft.copy(packages = if (isSelected(item))
                    draft.packages - sharedUid else draft.packages + sharedUid)
                adapter.notifyDataSetChanged()
                updateControls()
            }
        }
    }

    private val adapter = object : RecyclerView.Adapter<AppViewHolder>(), FastScrollRecyclerView.SectionedAdapter {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: AppViewHolder, position: Int) = holder.bind(visibleApps[position])
        override fun getItemCount() = visibleApps.size
        override fun getSectionName(position: Int) = visibleApps[position].name.firstOrNull()?.toString().orEmpty()
    }

    private fun filterApps() {
        val query = binding.search.text?.toString().orEmpty()
        visibleApps = apps.filter {
            (sysApps || !it.system) && (it.name.contains(query, true) ||
                it.packageName.contains(query, true) || it.uid.toString().contains(query))
        }
        adapter.notifyDataSetChanged()
    }

    private fun updateControls() {
        val explanation = getString(when {
            !draft.enabled -> R.string.ui_apps_off
            draft.bypass -> R.string.ui_apps_bypass
            else -> R.string.ui_apps_proxy
        })
        binding.selectionSummary.text = explanation + "\n" +
            getString(R.string.ui_selected_apps, draft.packages.size)
        binding.appProxyModeDisable.isEnabled = !saving && !recommending
        binding.appProxyModeOn.isEnabled = ready && !saving && !recommending
        binding.appProxyModeBypass.isEnabled = ready && !saving && !recommending
        binding.autoSelectProxyApps.isEnabled = ready && !saving && !recommending
        binding.showSystemApps.isEnabled = ready && !saving && !recommending
        binding.search.isEnabled = ready && !saving && !recommending
        invalidateOptionsMenu()
    }

    private fun renderList() {
        binding.loading.visibility = if (loading) View.VISIBLE else View.GONE
        binding.list.visibility = if (ready) View.VISIBLE else View.GONE
        binding.appPlaceholder.root.visibility = if (!loading && !ready) View.VISIBLE else View.GONE
        binding.appPlaceholder.emptyMessage.setText(if (snapshot.denied)
            R.string.app_routing_access_required else R.string.app_routing_load_failed)
        binding.appPlaceholder.openSettings.visibility = if (snapshot.denied) View.VISIBLE else View.GONE
        updateControls()
    }

    private fun loadApps() {
        if (saving) return
        loader?.cancel()
        loading = true
        renderList()
        loader = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val access = InstalledAppAccess.read(this@AppManagerActivity)
                    val rows = access.packages.orEmpty().values.mapNotNull { info ->
                        ensureActive()
                        info.applicationInfo?.let { ProxiedApp(it, it.loadLabel(packageManager).toString()) }
                    }
                    access to rows
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    Logs.w(error)
                    InstalledAppAccess.Snapshot() to emptyList<ProxiedApp>()
                }
            }
            snapshot = result.first
            apps = result.second.sortedWith(compareBy({ it.packageName !in draft.packages }, { it.name }))
            loading = false
            filterApps()
            renderList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        original = savedInstanceState?.config("original") ?: AppRoutingStore.read()
        draft = savedInstanceState?.config("draft") ?: original
        binding = LayoutAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.proxied_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        onBackPressedDispatcher.addCallback(this) { requestClose() }
        binding.toolbar.setNavigationOnClickListener { requestClose() }
        binding.appPlaceholder.openSettings.setOnClickListener {
            try { startActivity(InstalledAppAccess.settingsIntent(this)) }
            catch (error: Exception) { Logs.w(error); message(R.string.app_routing_access_required) }
        }
        binding.appPlaceholder.retry.setOnClickListener { loadApps() }
        checkMode()
        binding.bypassGroup.setOnCheckedStateChangeListener { _, checked ->
            if (saving) return@setOnCheckedStateChangeListener
            draft = when (checked.singleOrNull()) {
                R.id.appProxyModeDisable -> draft.copy(enabled = false)
                R.id.appProxyModeOn -> draft.copy(enabled = true, bypass = false)
                R.id.appProxyModeBypass -> draft.copy(enabled = true, bypass = true)
                else -> draft
            }
            updateControls()
        }
        binding.autoSelectProxyApps.setOnClickListener { selectProxyApps() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = adapter
        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
        binding.search.addTextChangedListener { filterApps() }
        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, checked -> sysApps = checked; filterApps() }
        updateControls()
    }

    private fun checkMode() = binding.bypassGroup.check(when {
        !draft.enabled -> R.id.appProxyModeDisable
        draft.bypass -> R.id.appProxyModeBypass
        else -> R.id.appProxyModeOn
    })

    override fun onResume() { super.onResume(); loadApps() }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putConfig("original", original)
        outState.putConfig("draft", draft)
        super.onSaveInstanceState(outState)
    }

    private fun Bundle.putConfig(key: String, config: AppRoutingConfig) {
        putBoolean("$key.enabled", config.enabled)
        putBoolean("$key.bypass", config.bypass)
        putStringArrayList("$key.packages", ArrayList(config.packages))
    }

    private fun Bundle.config(key: String): AppRoutingConfig? = if (!containsKey("$key.enabled")) null else
        AppRoutingConfig(getBoolean("$key.enabled"), getBoolean("$key.bypass"),
            getStringArrayList("$key.packages").orEmpty().toSet())

    private fun requestClose() {
        if (saving) return
        if (draft == original) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.ui_save) { _, _ -> save() }
            .setNegativeButton(R.string.ui_discard) { _, _ -> finish() }
            .setNeutralButton(R.string.ui_keep_editing, null).show()
    }

    private fun message(resource: Int) = Snackbar.make(binding.root, resource, Snackbar.LENGTH_LONG).apply {
        view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text).maxLines = 5
    }.show()

    private fun save() {
        if (saving || loading && draft.enabled) return
        saving = true
        updateControls()
        lifecycleScope.launch {
            try {
                // Recheck authorization and packages immediately before committing, not just on page entry.
                val access = if (draft.enabled) withContext(Dispatchers.IO) {
                    InstalledAppAccess.read(this@AppManagerActivity)
                } else snapshot
                val problem = draft.validate(access.packages?.keys, packageName)
                if (problem != null) {
                    snapshot = access
                    renderList()
                    message(InstalledAppAccess.problemMessage(problem))
                } else if (!AppRoutingStore.save(draft, original)) {
                    message(R.string.app_routing_changed_elsewhere)
                } else {
                    original = draft
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { Logs.w(error); message(R.string.app_routing_save_failed) }
            finally { saving = false; updateControls() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.per_app_proxy_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_save_app_routing)?.isEnabled = !saving && (!draft.enabled || ready)
        for (id in listOf(R.id.action_invert_selections, R.id.action_clear_selections,
            R.id.action_import_clipboard)) menu.findItem(id)?.isEnabled = ready && !saving
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> requestClose()
            R.id.action_save_app_routing -> save()
            R.id.action_export_clipboard -> message(if (SagerNet.trySetPrimaryClip(
                "${draft.bypass}\n${draft.packages.sorted().joinToString("\n")}"))
                R.string.action_export_msg else R.string.action_export_err)
            R.id.action_import_clipboard -> {
                if (!ready || saving) return true
                val lines = SagerNet.clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().split('\n', limit = 2)
                val bypass = lines[0].toBooleanStrictOrNull()
                if (bypass == null) message(R.string.action_import_err) else {
                    draft = AppRoutingConfig(true, bypass, AppRoutingConfig.parsePackages(lines.getOrElse(1) { "" }) - packageName)
                    checkMode(); adapter.notifyDataSetChanged(); updateControls()
                    message(R.string.action_import_msg)
                }
            }
            R.id.action_clear_selections -> {
                if (!ready || saving) return true
                draft = draft.copy(packages = emptySet())
                filterApps(); updateControls()
            }
            R.id.action_invert_selections -> {
                if (!ready || saving) return true
                val selectedUids = apps.filter { it.packageName in draft.packages }.map { it.uid }.toSet()
                val inverted = apps.filter { it.uid !in selectedUids }.map { it.packageName }.toSet()
                draft = draft.copy(packages = (draft.packages - apps.map { it.packageName }.toSet()) + inverted)
                filterApps(); updateControls()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun selectProxyApps() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.confirm)
            .setMessage(R.string.auto_select_proxy_apps_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                if (!ready || saving || recommending) return@setPositiveButton
                recommending = true
                updateControls()
                lifecycleScope.launch {
                    try {
                        val rules = withContext(Dispatchers.IO) {
                            ProxyAppRecommendations.load(this@AppManagerActivity)
                        }
                        val plan = ProxyAppRecommendations.plan(apps.map {
                            ProxyAppRecommendations.App(it.packageName, it.uid)
                        }, draft.packages, rules.packages, draft.enabled, draft.bypass)
                        draft = draft.copy(packages = plan.packages)
                        filterApps()
                        message(when {
                            rules.refreshFailed -> R.string.ui_auto_select_applied_offline
                            plan.changed == 0 -> R.string.ui_auto_select_no_changes
                            draft.bypass -> R.string.ui_auto_select_removed
                            else -> R.string.ui_auto_select_added
                        }, plan.changed, plan.matched)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Logs.w(error)
                        message(R.string.action_import_err)
                    } finally {
                        recommending = false
                        updateControls()
                    }
                }
            }.setNegativeButton(R.string.no, null).show()
    }

    private fun message(resource: Int, first: Int, second: Int) = Snackbar.make(binding.root,
        getString(resource, first, second), Snackbar.LENGTH_LONG).show()

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)
}
