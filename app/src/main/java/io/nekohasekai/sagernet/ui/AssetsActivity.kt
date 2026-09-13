package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.database.RouteRuleSet
import io.nekohasekai.sagernet.database.RuleSetDownloads
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import java.io.File
import java.util.UUID

/** Explicit local SRS/JSON imports; remote references are downloaded by sing-box. */
class AssetsActivity : ThemedActivity() {
    private lateinit var layout: LayoutAssetsBinding
    private val files = mutableListOf<File>()
    private val remotes = mutableListOf<RouteRuleSet>()
    private val statuses = mutableMapOf<String, RuleSetDownloads.Status>()
    private val busy = mutableSetOf<String>()
    private var updating = false
    private val downloads by lazy { RuleSetDownloads(filesDir) }
    private val directory get() = File(filesDir, "rule-sets")
    private val adapter = object : RecyclerView.Adapter<AssetHolder>() {
        override fun getItemCount() = remotes.size + files.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: AssetHolder, index: Int) {
            if (index < remotes.size) holder.bindRemote(remotes[index]) else holder.bind(files[index - remotes.size])
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layout = LayoutAssetsBinding.inflate(layoutInflater)
        setContentView(layout.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply { setTitle(R.string.route_set_files); setDisplayHomeAsUpEnabled(true) }
        onBackPressedDispatcher.addCallback(this) { finish() }
        layout.recyclerView.layoutManager = FixedLinearLayoutManager(layout.recyclerView)
        layout.recyclerView.adapter = adapter
        layout.refreshLayout.setOnRefreshListener { update(remotes.toList()) }
        reload()
    }

    private fun reload() = lifecycleScope.launch {
        try {
            val (found, refs) = withContext(Dispatchers.IO) {
                directory.listFiles()?.filter { it.isFile && it.extension in setOf("srs", "json") }?.sortedBy { it.name }.orEmpty() to
                    RuleSetDownloads.references(SagerDatabase.rulesDao.allRules())
            }
            val state = withContext(Dispatchers.IO) { refs.associate { RuleSetDownloads.key(it) to downloads.status(it) } }
            files.clear(); files.addAll(found); remotes.clear(); remotes.addAll(refs)
            statuses.clear(); statuses.putAll(state)
            adapter.notifyDataSetChanged(); invalidateOptionsMenu()
            layout.resourceHint.text = getString(if (refs.isEmpty() && found.isEmpty()) R.string.route_set_manager_empty else R.string.route_set_update_hint)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { snackbarInternal(io.nekohasekai.sagernet.utils.UserFacingError.describe(e)).show() }
        finally { if (!updating) layout.refreshLayout.isRefreshing = false }
    }

    private fun update(refs: List<RouteRuleSet>) {
        if (updating) return
        if (refs.isEmpty()) { layout.refreshLayout.isRefreshing = false; snackbarInternal(getString(R.string.route_set_no_remote)).show(); return }
        updating = true; invalidateOptionsMenu()
        lifecycleScope.launch {
            var failures = 0
            try {
                for (ref in refs) {
                    val key = RuleSetDownloads.key(ref); busy.add(key); adapter.notifyDataSetChanged()
                    try {
                        val result = downloads.update(ref, { file, format -> Libcore.validateRuleSet(file.absolutePath, format) })
                        statuses[key] = result
                        if (result.error.isNotEmpty()) failures++
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { failures++; statuses[key] = (statuses[key] ?: RuleSetDownloads.Status()).copy(error = io.nekohasekai.sagernet.utils.UserFacingError.describe(e)) }
                    finally { busy.remove(key); adapter.notifyDataSetChanged() }
                }
                snackbarInternal(getString(R.string.route_set_update_result, refs.size - failures, failures)).show()
            } finally {
                updating = false; layout.refreshLayout.isRefreshing = false
                adapter.notifyDataSetChanged(); invalidateOptionsMenu()
            }
        }
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val display = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
                    val name = File(display).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    require(name.substringAfterLast('.', "") in setOf("srs", "json")) { getString(R.string.route_set_import_error) }
                    directory.mkdirs()
                    val staging = File.createTempFile("import-", ".tmp", directory)
                    try {
                        contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input)
                            staging.outputStream().use { output ->
                                val buffer = ByteArray(8192); var total = 0L
                                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 32L * 1024 * 1024) { "Rule set exceeds 32 MiB" }; output.write(buffer, 0, n) }
                            }
                        }
                        Libcore.validateRuleSet(staging.absolutePath, if (name.endsWith(".srs")) "binary" else "source")
                        // Never overwrite a file referenced by existing rules.
                        val destination = File(directory, "${UUID.randomUUID().toString().take(8)}-$name")
                        check(staging.renameTo(destination)) { "Cannot store rule set" }
                    } finally { staging.delete() }
                }
                reload()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { snackbarInternal(io.nekohasekai.sagernet.utils.UserFacingError.describe(e)).show() }
        }
    }

    inner class AssetHolder(private val binding: LayoutAssetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindRemote(ref: RouteRuleSet) {
            val key = RuleSetDownloads.key(ref)
            val state = statuses[key] ?: RuleSetDownloads.Status()
            binding.assetName.text = ref.name
            val checked = if (state.checked == 0L) {
                if (io.nekohasekai.sagernet.database.BundledRuleSets.contains(ref)) "使用内置规则"
                else getString(R.string.route_set_never_updated)
            } else
                getString(R.string.route_set_last_checked, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.checked)))
            binding.assetSource.isVisible = true
            binding.assetSource.text = ref.source
            binding.assetStatus.text = checked
            binding.assetError.isVisible = state.error.isNotEmpty()
            binding.assetError.text = if (state.error.isEmpty()) "" else getString(R.string.route_set_update_failed, io.nekohasekai.sagernet.utils.UserFacingError.describe(state.error))
            binding.rulesUpdate.isInvisible = false
            binding.rulesUpdate.isEnabled = !updating
            binding.rulesUpdate.setOnClickListener { update(listOf(ref)) }
            binding.rulesUpdate.contentDescription = getString(R.string.route_set_update_named, ref.name)
            binding.subscriptionUpdateProgress.isInvisible = key !in busy
            binding.root.setOnLongClickListener(null)
        }
        fun bind(file: File) {
            binding.assetSource.isVisible = false
            binding.assetError.isVisible = false
            binding.assetName.text = file.name
            binding.assetStatus.text = "${file.length()} bytes • ${file.extension.uppercase()}"
            binding.rulesUpdate.isInvisible = true
            binding.rulesUpdate.setOnClickListener(null)
            binding.subscriptionUpdateProgress.isInvisible = true
            binding.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@AssetsActivity).setTitle(R.string.delete).setMessage(file.name)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.delete) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    check(SagerDatabase.rulesDao.allRules().none { row -> RouteRuleSet.decode(row.ruleSets).any { it.source == file.absolutePath || it.source == "rule-sets/" + file.name } || row.config.contains(file.absolutePath) }) { getString(R.string.route_set_in_use) }
                                    check(file.delete()) { "Cannot delete rule set" }
                                }
                                reload()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { snackbarInternal(io.nekohasekai.sagernet.utils.UserFacingError.describe(e)).show() }
                        }
                    }.show()
                true
            }
        }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.import_asset_menu, menu); return true }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_update_rule_sets)?.isEnabled = !updating && remotes.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_update_rule_sets) { update(remotes.toList()); return true }
        if (item.itemId == R.id.action_import_file) { importFile.launch("*/*"); return true }
        return super.onOptionsItemSelected(item)
    }
    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
