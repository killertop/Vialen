package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.database.RouteRuleSet
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
    private val directory get() = File(filesDir, "rule-sets")
    private val adapter = object : RecyclerView.Adapter<AssetHolder>() {
        override fun getItemCount() = files.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        override fun onBindViewHolder(holder: AssetHolder, index: Int) = holder.bind(files[index])
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
        layout.refreshLayout.setOnRefreshListener { reload() }
        reload()
    }

    private fun reload() = lifecycleScope.launch {
        val found = withContext(Dispatchers.IO) { directory.listFiles()?.filter { it.isFile && it.extension in setOf("srs", "json") }?.sortedBy { it.name }.orEmpty() }
        files.clear(); files.addAll(found); adapter.notifyDataSetChanged(); layout.refreshLayout.isRefreshing = false
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
            catch (e: Exception) { snackbarInternal(e.message ?: getString(R.string.route_set_import_error)).show() }
        }
    }

    inner class AssetHolder(private val binding: LayoutAssetItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.assetName.text = file.name
            binding.assetStatus.text = "${file.length()} bytes • ${file.extension.uppercase()}"
            binding.rulesUpdate.isInvisible = true
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
                            catch (e: Exception) { snackbarInternal(e.message.orEmpty()).show() }
                        }
                    }.show()
                true
            }
        }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.import_asset_menu, menu); return true }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_import_file) { importFile.launch("*/*"); return true }
        return super.onOptionsItemSelected(item)
    }
    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
