package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.size
import androidx.core.widget.TextViewCompat
import java.io.IOException
import java.io.OutputStream
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.bg.proto.runUrlTestBatch
import io.nekohasekai.sagernet.database.ConnectionTestResult
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.closeQuietly
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnLifecycleDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.scrollTo
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.UrlTestDialog
import io.nekohasekai.sagernet.widget.UrlTestDialogState
import io.nekohasekai.sagernet.widget.UrlTestPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import moe.matsuri.nb4a.ui.ConnectionTestNotification
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener,
    OnPreferenceDataStoreChangeListener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    val isAdapterInitialized: Boolean
        get() = ::adapter.isInitialized
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    private var pendingExportProfileId: Long? = null
    private var pendingImportGroupId: Long? = null
    private var pendingImportOriginGroupId: Long? = null
    private var urlTestDialog: UrlTestDialog? = null
    private var backgroundUrlTest: (() -> Unit)? = null

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    fun getCurrentGroupFragment(): GroupFragment? {
        return try {
            childFragmentManager.findFragmentByTag("f" + DataStore.selectedGroup) as GroupFragment?
        } catch (e: Exception) {
            Logs.e(e)
            null
        }
    }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (adapter.groupList.size > position && position >= 0) {
                adapter.selectedGroupIndex = position
                DataStore.selectedGroup = adapter.groupList[position].id
                val group = adapter.groupList[position]
                updateGroupActions(group, adapter.groupFragments[group.id]?.adapter?.itemCount?.let { it > 0 } == true)
            }
        }
    }

    @SuppressLint("DetachAndAttachSameFragment")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportProfileId = savedInstanceState?.getLong("pendingExportProfileId")?.takeIf { it > 0 }
        pendingImportGroupId = savedInstanceState?.getLong("pendingImportGroupId")?.takeIf { it > 0 }
        pendingImportOriginGroupId = savedInstanceState?.getLong("pendingImportOriginGroupId")?.takeIf { it > 0 }

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExportProfileId?.let { outState.putLong("pendingExportProfileId", it) }
        pendingImportGroupId?.let { outState.putLong("pendingImportGroupId", it) }
        pendingImportOriginGroupId?.let { outState.putLong("pendingImportOriginGroupId", it) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Import guidance belongs to the home page, not the node-selection dialog.
        view.findViewById<View>(R.id.home_import_hint).isVisible = !select
        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
            toolbar.menu.findItem(R.id.action_misc)?.subMenu?.let {
                androidx.core.view.MenuCompat.setGroupDividerEnabled(it, true)
            }
            toolbar.menu.findItem(R.id.action_misc)?.isVisible = false
        } else {
            if (titleRes != 0) {
                toolbar.setTitle(titleRes)
            }
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }

        if (arguments?.getBoolean("openAddNode") == true) {
            arguments?.remove("openAddNode")
            view.post { if (isAdded && this.view != null) showAddNodeSheet() }
        }
        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {
            val fragment = getCurrentGroupFragment()

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex =
                    fragment.adapter!!.configurationIdList.indexOf(selectedProxy)
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        DataStore.profileCacheStore.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (!isAdded || view == null || !::adapter.isInitialized) return@runOnMainDispatcher
            // editingGroup
            if (key == Key.PROFILE_GROUP) {
                val targetId = DataStore.editingGroup
                if (targetId > 0 && targetId != DataStore.selectedGroup) {
                    DataStore.selectedGroup = targetId
                    val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }
                    if (targetIndex >= 0) {
                        groupPager.setCurrentItem(targetIndex, false)
                    } else {
                        adapter.reload(explicitTargetGroupId = targetId)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        backgroundUrlTest?.invoke()
        backgroundUrlTest = null
        urlTestDialog?.dismiss()
        urlTestDialog = null
        DataStore.profileCacheStore.unregisterChangeListener(this)

        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroyView()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = getCurrentGroupFragment()
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    private fun showMessage(message: CharSequence) {
        runOnMainDispatcher {
            val owner = activity as? MainActivity
            if (owner != null && !owner.isFinishing && !owner.isDestroyed) {
                owner.snackbar(message).show()
            }
        }
    }

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            val targetId = pendingImportGroupId
            val originGroupId = pendingImportOriginGroupId
            pendingImportGroupId = null
            pendingImportOriginGroupId = null
            if (file == null) return@registerForActivityResult
            if (targetId == null) {
                showMessage(app.getString(R.string.profile_import_target_missing))
                return@registerForActivityResult
            }
            val resolver = app.contentResolver
            val owner = activity as? MainActivity
            runOnDefaultDispatcher {
                try {
                    val fileName = resolver.query(file, null, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            .takeIf { it >= 0 }?.let(cursor::getString)
                    }
                    val proxies = mutableListOf<AbstractBean>()
                    if (fileName?.endsWith(".zip", ignoreCase = true) == true) {
                        // A broken entry must close the archive as well as the underlying provider stream.
                        checkNotNull(resolver.openInputStream(file)).use { input ->
                            ZipInputStream(input).use { zip ->
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (!entry.isDirectory) {
                                        RawUpdater.parseRaw(zip.readBytes().toString(Charsets.UTF_8), entry.name)
                                            ?.let { proxies.addAll(it) }
                                    }
                                    zip.closeEntry()
                                }
                            }
                        }
                    } else {
                        val fileText = checkNotNull(resolver.openInputStream(file)).bufferedReader().use {
                            it.readText()
                        }
                        RawUpdater.parseRaw(fileText, fileName ?: "")?.let { proxies.addAll(it) }
                    }
                    if (proxies.isEmpty()) {
                        showMessage(app.getString(R.string.no_proxies_found_in_file))
                    } else {
                        import(proxies, targetId, originGroupId)
                    }
                } catch (e: SubscriptionFoundException) {
                    if (owner != null && !owner.isFinishing && !owner.isDestroyed) {
                        owner.importSubscription(e.link.toUri())
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    showMessage(e.readableMessage)
                }
            }
        }

    suspend fun import(proxies: List<AbstractBean>, targetId: Long, originGroupId: Long? = null) {
        ProfileManager.createProfilesForImport(targetId, proxies)
        onMainDispatcher {
            val owner = activity as? MainActivity
            if (owner != null && !owner.isFinishing && !owner.isDestroyed) {
                if (isAdded && view != null && DataStore.selectedGroup == originGroupId) {
                    DataStore.editingGroup = targetId
                }
                owner.snackbar(getString(R.string.ui_import_next)).show()
            }
        }

    }

    fun pendingRemovalIds(): List<Long> = if (::adapter.isInitialized) {
        adapter.groupFragments.values.flatMap { it.adapter?.pendingRemovalIds().orEmpty() }
    } else emptyList()

    fun showAddNodeSheet() {
        val context = requireContext()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp2px(24), dp2px(24), dp2px(24), dp2px(32))
        }
        content.addView(android.widget.TextView(context).apply {
            setText(R.string.ui_add_node)
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Vialen_PanelTitle)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.vialen_text_primary))
            setPadding(0, 0, 0, dp2px(16))
        })
        val actions = PopupMenu(context, toolbar).menu.apply { requireActivity().menuInflater.inflate(R.menu.node_creation_menu, this) }
        fun action(title: Int, run: () -> Unit) {
            content.addView(com.google.android.material.button.MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(title)
                minimumHeight = dp2px(48)
                cornerRadius = dp2px(12)
                isAllCaps = false
                setOnClickListener { sheet.dismiss(); run() }
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        action(R.string.ui_add_subscription) {
            startActivity(Intent(context, GroupSettingsActivity::class.java).putExtra("newSubscription", true))
        }
        action(R.string.add_profile_methods_scan_qr_code) { onMenuItemClick(actions.findItem(R.id.action_scan_qr_code)) }
        action(R.string.action_import) { onMenuItemClick(actions.findItem(R.id.action_import_clipboard)) }
        action(R.string.action_import_file) { onMenuItemClick(actions.findItem(R.id.action_import_file)) }
        action(R.string.ui_manual_config) { showProtocolPicker(actions) }
        content.addView(android.widget.TextView(context).apply {
            setText(R.string.ui_import_hint)
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Vialen_Secondary)
            setPadding(0, dp2px(16), 0, 0)
        })
        sheet.setContentView(content)
        sheet.show()
    }

    private fun showProtocolPicker(menu: android.view.Menu) {
        val items = (0 until menu.size()).map { menu.getItem(it) }.filter {
            it.itemId !in setOf(R.id.action_scan_qr_code, R.id.action_import_clipboard, R.id.action_import_file)
        }
        val context = requireContext()
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp2px(24), 0, dp2px(24), 0)
        }
        val search = android.widget.EditText(context).apply {
            setHint(R.string.ui_search_protocol)
            setSingleLine()
        }
        val list = android.widget.ListView(context)
        val commonIds = setOf(R.id.action_new_vless, R.id.action_new_ss, R.id.action_new_vmess, R.id.action_new_trojan)
        var rows = emptyList<Pair<CharSequence, MenuItem?>>()
        fun filter(query: String) {
            rows = if (query.isBlank()) {
                listOf(getString(R.string.ui_common_protocols) to null) +
                    items.filter { it.itemId in commonIds }.map { it.title!! to it } +
                    listOf(getString(R.string.ui_other_protocols) to null) +
                    items.filter { it.itemId !in commonIds }.map { it.title!! to it }
            } else items.filter { it.title.toString().contains(query, ignoreCase = true) }.map { it.title!! to it }
            list.adapter = object : android.widget.ArrayAdapter<CharSequence>(context,
                android.R.layout.simple_list_item_1, rows.map { it.first }) {
                override fun areAllItemsEnabled() = false
                override fun isEnabled(position: Int) = rows[position].second != null
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val row = super.getView(position, convertView, parent) as android.widget.TextView
                    val heading = !isEnabled(position)
                    TextViewCompat.setTextAppearance(row, if (heading) R.style.TextAppearance_Vialen_Section
                        else R.style.TextAppearance_Vialen_Body)
                    row.setTextColor(androidx.core.content.ContextCompat.getColor(context,
                        if (heading) R.color.vialen_text_secondary else R.color.vialen_text_primary))
                    return row
                }
            }
        }
        content.addView(search)
        content.addView(list, android.widget.LinearLayout.LayoutParams(-1, dp2px(320)))
        filter("")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.ui_choose_protocol)
            .setView(content).setNegativeButton(android.R.string.cancel, null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val item = rows[position].second ?: return@setOnItemClickListener
            dialog.dismiss()
            onMenuItemClick(item)
        }
        dialog.show()
    }

    private fun updateGroupActions(group: ProxyGroup, hasNodes: Boolean) {
        if (select || view == null || group.id != DataStore.selectedGroup) return
        val subscription = group.type == GroupType.SUBSCRIPTION
        toolbar.menu.findItem(R.id.action_misc)?.isVisible = hasNodes || subscription
        toolbar.menu.findItem(R.id.action_update_subscription)?.isVisible = subscription
        for (id in intArrayOf(R.id.action_clear_traffic_statistics, R.id.action_remove_duplicate,
            R.id.action_connection_tcp_ping, R.id.action_connection_url_test,
            R.id.action_connection_test_clear_results, R.id.action_connection_test_delete_unavailable,
            R.id.action_order)) toolbar.menu.findItem(id)?.isVisible = hasNodes
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_add -> showAddNodeSheet()
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }

            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    val originGroupId = DataStore.selectedGroup
                    val targetId = try {
                        DataStore.selectedGroupForImport()
                    } catch (error: Exception) {
                        Logs.w(error)
                        snackbar(error.readableMessage).show()
                        return true
                    }
                    val owner = activity as? MainActivity
                    runOnDefaultDispatcher {
                        try {
                            val proxies = RawUpdater.parseRaw(text)
                            if (proxies.isNullOrEmpty()) {
                                showMessage(app.getString(R.string.no_proxies_found_in_clipboard))
                            } else {
                                import(proxies, targetId, originGroupId)
                            }
                        } catch (e: SubscriptionFoundException) {
                            if (owner != null && !owner.isFinishing && !owner.isDestroyed) {
                                owner.importSubscription(e.link.toUri())
                            }
                        } catch (e: Exception) {
                            Logs.w(e)
                            showMessage(e.readableMessage)
                        }
                    }
                }
            }

            R.id.action_import_file -> {
                pendingImportOriginGroupId = DataStore.selectedGroup
                pendingImportGroupId = try {
                    DataStore.selectedGroupForImport()
                } catch (error: Exception) {
                    pendingImportOriginGroupId = null
                    Logs.w(error)
                    snackbar(error.readableMessage).show()
                    return true
                }
                if (!startFilesForResult(importFile, "*/*")) {
                    pendingImportGroupId = null
                    pendingImportOriginGroupId = null
                }
            }

            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }

            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }

            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }

            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }

            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java).apply {
                    putExtra("vless", true)
                })
            }

            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }

            R.id.action_new_hysteria -> {
                startActivity(Intent(requireActivity(), HysteriaSettingsActivity::class.java))
            }

            R.id.action_new_tuic -> {
                startActivity(Intent(requireActivity(), TuicSettingsActivity::class.java))
            }

            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }

            R.id.action_new_shadowtls -> {
                startActivity(Intent(requireActivity(), ShadowTLSSettingsActivity::class.java))
            }

            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }

            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingActivity::class.java))
            }

            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }

            R.id.action_update_subscription -> {
                val group = DataStore.currentGroup()
                if (group.type != GroupType.SUBSCRIPTION) {
                    snackbar(R.string.group_not_subscription).show()
                    Logs.e("onMenuItemClick: Group(${group.displayName()}) is not subscription")
                } else {
                    runOnLifecycleDispatcher {
                        GroupUpdater.startUpdate(group, true)
                    }
                }
            }

            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    ProfileManager.clearTraffic(DataStore.currentGroupId())
                }
            }

            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0) {
                            profile.status = 0
                            profile.ping = 0
                            profile.error = null
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        ProfileManager.updateProfile(toClear)
                    }
                }
            }

            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    if (profiles.isNotEmpty()) for (profile in profiles) {
                        if (profile.status != 0 && profile.status != 1) {
                            toClear.add(profile)
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(getString(R.string.ui_delete_nodes, toClear.size))
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                        val targetAdapter = onMainDispatcher {
                                            (activity as? MainActivity)?.refreshProfileAvailability()
                                            adapter.groupFragments[toClear.first().groupId]?.adapter
                                        }
                                        targetAdapter?.reloadProfiles()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
                    val toClear = mutableListOf<ProxyEntity>()
                    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
                    for (pf in profiles) {
                        val proxy = Protocols.Deduplication(pf.requireBean(), pf.displayType())
                        if (!uniqueProxies.add(proxy)) {
                            toClear += pf
                        }
                    }
                    if (toClear.isNotEmpty()) {
                        onMainDispatcher {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                                .setMessage(
                                    getString(R.string.ui_delete_nodes, toClear.size) + "\n" +
                                            toClear.mapIndexedNotNull { index, proxyEntity ->
                                                if (index < 20) {
                                                    proxyEntity.displayName()
                                                } else if (index == 20) {
                                                    "......"
                                                } else {
                                                    null
                                                }
                                            }.joinToString("\n")
                                )
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    for (profile in toClear) {
                                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                                            val index = configurationIdList.indexOf(profile.id)
                                            if (index >= 0) {
                                                configurationIdList.removeAt(index)
                                                configurationList.remove(profile.id)
                                                notifyItemRemoved(index)
                                            }
                                        }
                                    }
                                    runOnDefaultDispatcher {
                                        for (profile in toClear) {
                                            ProfileManager.deleteProfile2(
                                                profile.groupId, profile.id
                                            )
                                        }
                                        val targetAdapter = onMainDispatcher {
                                            (activity as? MainActivity)?.refreshProfileAvailability()
                                            adapter.groupFragments[toClear.first().groupId]?.adapter
                                        }
                                        targetAdapter?.reloadProfiles()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                }
            }

            R.id.action_connection_tcp_ping -> {
                pingTest(false)
            }

            R.id.action_connection_url_test -> {
                urlTest()
            }
        }
        return true
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setPositiveButton(R.string.minimize) { _, _ ->
                minimize()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                cancel()
            }
            .setCancelable(false)

        lateinit var cancel: () -> Unit
        lateinit var minimize: () -> Unit

        val dialogStatus = AtomicInteger(0) // 1: hidden 2: cancelled
        var notification: ConnectionTestNotification? = null

        val results: MutableSet<ProxyEntity> = ConcurrentHashMap.newKeySet()
        var proxyN = 0
        val finishedN = AtomicInteger(0)

        fun update(profile: ProxyEntity) {
            if (dialogStatus.get() != 2) {
                results.add(profile)
            }
            runOnMainDispatcher {
                val context = context ?: return@runOnMainDispatcher
                val progress = finishedN.addAndGet(1)
                val status = dialogStatus.get()
                notification?.updateNotification(
                    progress,
                    proxyN,
                    progress >= proxyN || status == 2
                )
                if (status >= 1) return@runOnMainDispatcher
                if (!isAdded) return@runOnMainDispatcher

                // refresh dialog

                var profileStatusText: String? = null
                var profileStatusColor = 0

                when (profile.status) {
                    -1 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    0 -> {
                        profileStatusText = getString(R.string.connection_test_testing)
                        profileStatusColor = context.getColorAttr(android.R.attr.textColorSecondary)
                    }

                    1 -> {
                        profileStatusText = getString(R.string.available, profile.ping)
                        profileStatusColor = context.getColour(R.color.vialen_success)
                    }

                    2 -> {
                        profileStatusText = profile.error
                        profileStatusColor = context.getColour(R.color.vialen_error)
                    }

                    3 -> {
                        val err = profile.error ?: ""
                        val msg = Protocols.genFriendlyMsg(err)
                        profileStatusText = if (msg != err) msg else getString(R.string.unavailable)
                        profileStatusColor = context.getColour(R.color.vialen_error)
                    }
                }

                val text = SpannableStringBuilder().apply {
                    append("\n" + profile.displayName())
                    append("\n")
                    append(
                        profile.displayType(),
                        ForegroundColorSpan(context.getProtocolColor(profile.type)),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append(" ")
                    append(
                        profileStatusText,
                        ForegroundColorSpan(profileStatusColor),
                        SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    append("\n")
                }

                binding.nowTesting.text = text
                binding.progress.text = "$progress / $proxyN"
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun pingTest(icmpPing: Boolean) {
        if (DataStore.runningTest) return else DataStore.runningTest = true
        val test = TestDialog()
        val dialog = test.builder.show()
        val testJobs = mutableListOf<Job>()
        val group = DataStore.currentGroup()

        val mainJob = runOnDefaultDispatcher {
            val profilesList = SagerDatabase.proxyDao.getByGroup(group.id).filter {
                if (icmpPing) {
                    if (it.requireBean().canICMPing()) {
                        return@filter true
                    }
                } else {
                    if (it.requireBean().canTCPing()) {
                        return@filter true
                    }
                }
                return@filter false
            }
            test.proxyN = profilesList.size
            val profiles = ConcurrentLinkedQueue(profilesList)
            repeat(DataStore.connectionTestConcurrent) {
                testJobs.add(launch(Dispatchers.IO) {
                    while (isActive) {
                        val profile = profiles.poll() ?: break

                        profile.status = 0
                        var address = profile.requireBean().serverAddress
                        if (!address.isIpAddress()) {
                            try {
                                SagerNet.underlyingNetwork!!.getAllByName(address).apply {
                                    if (isNotEmpty()) {
                                        address = this[0].hostAddress
                                    }
                                }
                            } catch (ignored: UnknownHostException) {
                            }
                        }
                        if (!isActive) break
                        if (!address.isIpAddress()) {
                            profile.status = 2
                            profile.error = app.getString(R.string.connection_test_domain_not_found)
                            test.update(profile)
                            continue
                        }
                        try {
                            if (icmpPing) {
                                // removed
                            } else {
                                val socket =
                                    SagerNet.underlyingNetwork?.socketFactory?.createSocket()
                                        ?: Socket()
                                try {
                                    socket.soTimeout = 3000
                                    socket.bind(InetSocketAddress(0))
                                    val start = SystemClock.elapsedRealtime()
                                    socket.connect(
                                        InetSocketAddress(
                                            address, profile.requireBean().serverPort
                                        ), 3000
                                    )
                                    if (!isActive) break
                                    profile.status = 1
                                    profile.ping = (SystemClock.elapsedRealtime() - start).toInt()
                                    test.update(profile)
                                } finally {
                                    socket.closeQuietly()
                                }
                            }
                        } catch (e: Exception) {
                            if (!isActive) break
                            val message = e.readableMessage

                            if (icmpPing) {
                                profile.status = 2
                                profile.error = getString(R.string.connection_test_unreachable)
                            } else {
                                profile.status = 2
                                when {
                                    !message.contains("failed:") -> profile.error =
                                        getString(R.string.connection_test_timeout)

                                    else -> when {
                                        message.contains("ECONNREFUSED") -> {
                                            profile.error =
                                                getString(R.string.connection_test_refused)
                                        }

                                        message.contains("ENETUNREACH") -> {
                                            profile.error =
                                                getString(R.string.connection_test_unreachable)
                                        }

                                        else -> {
                                            profile.status = 3
                                            profile.error = message
                                        }
                                    }
                                }
                            }
                            test.update(profile)
                        }
                    }
                })
            }

            testJobs.joinAll()

            runOnMainDispatcher {
                test.cancel()
            }
        }
        test.cancel = {
            test.dialogStatus.set(2)
            dialog.dismiss()
            runOnDefaultDispatcher {
                mainJob.cancel()
                testJobs.forEach { it.cancel() }
                test.results.forEach {
                    try {
                        ProfileManager.updateProfile(it)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
                GroupManager.postReload(DataStore.currentGroupId())
                DataStore.runningTest = false
            }
        }
        test.minimize = {
            test.dialogStatus.set(1)
            test.notification = ConnectionTestNotification(
                dialog.context,
                "[${group.displayName()}] ${getString(R.string.connection_test)}"
            )
            dialog.hide()
        }
    }

    private data class CompletedUrlTest(
        val result: ConnectionTestResult,
        val name: String,
        val protocol: String,
        val description: String,
    )

    @OptIn(DelicateCoroutinesApi::class)
    fun urlTest() {
        if (DataStore.runningTest) return
        val group = DataStore.currentGroup()
        val application = requireContext().applicationContext
        val state = AtomicReference(UrlTestDialogState())
        val background = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
        val startedAt = SystemClock.elapsedRealtime()
        val concurrency = DataStore.connectionTestConcurrent
        val urlTest = UrlTest() // One immutable URL/timeout snapshot for this entire run.
        lateinit var mainJob: Job
        val dialog = UrlTestDialog(requireContext(), group.displayName(), onStop = {
            // The paced view may still show Stop just after the batch has finished.
            // Never turn an already terminal session back into an unfinishable stopping state.
            val phase = state.get().phase
            if (phase == UrlTestPhase.PREPARING || phase == UrlTestPhase.RUNNING) {
                stopRequested.set(true)
                state.updateAndGet { it.copy(phase = UrlTestPhase.STOPPING) }
                mainJob.cancel()
            }
        }, onBackground = {
            backgroundUrlTest?.invoke()
            urlTestDialog?.dismiss()
            urlTestDialog = null
        })
        urlTestDialog = dialog
        backgroundUrlTest = { background.set(true) }
        val dialogRef = WeakReference(dialog)
        val notification = ConnectionTestNotification(
            application, "[${group.displayName()}] ${getString(R.string.url_test_dialog_title)}"
        )
        dialog.show()
        DataStore.runningTest = true

        // One paced renderer replaces a main-thread job and notification per node.
        // The background run retains no Activity; destroying the view hands progress to the notification.
        runOnMainDispatcher {
            var rendered: UrlTestDialogState? = null
            var notified = -1
            while (true) {
                val current = state.get()
                val terminal = current.phase == UrlTestPhase.FINISHED ||
                    current.phase == UrlTestPhase.STOPPED || current.phase == UrlTestPhase.ERROR
                val visible = if (terminal) current else current.copy(
                    elapsedMillis = ((SystemClock.elapsedRealtime() - startedAt) / 1000) * 1000
                )
                if (!terminal && background.get() && notified != current.completed) {
                    notification.updateNotification(current.completed, current.total, false)
                    notified = current.completed
                }
                if (!background.get() && visible != rendered) {
                    dialogRef.get()?.render(visible)
                    rendered = visible
                }
                if (terminal) {
                    if (current.phase == UrlTestPhase.FINISHED) {
                        // Briefly expose the final counts, then return to the updated node list.
                        delay(1200)
                        dialogRef.get()?.dismiss()
                    }
                    break
                }
                delay(150)
            }
        }

        mainJob = runOnMainDispatcher {
            var failure: Throwable? = null
            try {
                withContext(Dispatchers.Default) {
                    runUrlTestBatch(
                        load = { SagerDatabase.proxyDao.getByGroup(group.id) },
                        concurrency = concurrency,
                        test = { profile ->
                            val result = try {
                                ConnectionTestResult(profile.id, 1, urlTest.doTest(profile), null)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                // Native request aborts and cleanup failures must not become failed nodes.
                                if (!currentCoroutineContext().isActive) Logs.w(error)
                                currentCoroutineContext().ensureActive()
                                ConnectionTestResult(profile.id, 3, 0, error.readableMessage)
                            }
                            val description = if (result.status == 1) {
                                application.getString(R.string.available, result.ping)
                            } else {
                                val error = result.error.orEmpty()
                                Protocols.genFriendlyMsg(error).takeIf { it != error }
                                    ?: application.getString(R.string.unavailable)
                            }
                            CompletedUrlTest(result, profile.displayName(), profile.displayType(), description)
                        },
                        save = { results ->
                            ProfileManager.updateConnectionTestResults(results.map { it.result })
                            if (results.isNotEmpty()) GroupManager.postReload(group.id)
                        },
                        onStarted = { total ->
                            state.updateAndGet { it.copy(total = total, phase =
                                if (stopRequested.get()) UrlTestPhase.STOPPING else UrlTestPhase.RUNNING) }
                        },
                        onResult = { completed ->
                            val available = completed.result.status == 1
                            state.updateAndGet { it.copy(
                                completed = it.completed + 1,
                                available = it.available + if (available) 1 else 0,
                                failed = it.failed + if (available) 0 else 1,
                                lastName = completed.name,
                                lastProtocol = completed.protocol,
                                lastResult = completed.description,
                                lastSuccess = available,
                            ) }
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                // A failed save during cancellation is attached by runUrlTestBatch.
                failure = cancelled.suppressed.firstOrNull()
                failure?.let { Logs.w(it) }
            } catch (error: Exception) {
                failure = error
                Logs.w(error)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    // The batch has now joined every native worker and persisted its stable snapshot.
                    // Clear the shared notification before another run can acquire the running flag.
                    val completed = state.get()
                    notification.updateNotification(completed.completed, completed.total, true)
                    DataStore.runningTest = false
                    state.updateAndGet { it.copy(
                        phase = when {
                            failure != null -> UrlTestPhase.ERROR
                            stopRequested.get() -> UrlTestPhase.STOPPED
                            else -> UrlTestPhase.FINISHED
                        },
                        elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                        error = failure?.readableMessage,
                    ) }
                }
            }
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        var reloadGeneration = 0L
            private set
        var publishedGeneration = 0L
            private set

        fun reload(now: Boolean = false, explicitTargetGroupId: Long? = null) {
            val expectedGeneration = ++reloadGeneration
            val expectedView = view
            val expectedAdapter = this
            val requestedTargetId = explicitTargetGroupId

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                val emptyUngrouped = newGroupList.filter { it.ungrouped && SagerDatabase.proxyDao.countByGroup(it.id) == 0L }
                if (emptyUngrouped.isNotEmpty() && newGroupList.size > emptyUngrouped.size) {
                    newGroupList.removeAll(emptyUngrouped)
                } else if (emptyUngrouped.size == newGroupList.size && newGroupList.size > 1) {
                    newGroupList.removeAll(emptyUngrouped.drop(1))
                }

                val runFunc = if (now) activity?.let { it::runOnUiThread } else groupPager::post
                if (runFunc != null) {
                    runFunc {
                        if (!isAdded || view !== expectedView || adapter !== expectedAdapter || reloadGeneration != expectedGeneration) {
                            return@runFunc
                        }

                        val currentPos = groupPager.currentItem
                        val desiredGroupId = requestedTargetId ?: selectedItem?.groupId ?: if (!select) {
                            val candidate = DataStore.selectedGroup
                            if (candidate > 0L && newGroupList.any { it.id == candidate }) {
                                candidate
                            } else if (currentPos in groupList.indices && newGroupList.any { it.id == groupList[currentPos].id }) {
                                groupList[currentPos].id
                            } else if (selectedGroupIndex in groupList.indices && newGroupList.any { it.id == groupList[selectedGroupIndex].id }) {
                                groupList[selectedGroupIndex].id
                            } else {
                                DataStore.currentGroupId()
                            }
                        } else {
                            if (currentPos in groupList.indices && newGroupList.any { it.id == groupList[currentPos].id }) {
                                groupList[currentPos].id
                            } else if (selectedGroupIndex in groupList.indices && newGroupList.any { it.id == groupList[selectedGroupIndex].id }) {
                                groupList[selectedGroupIndex].id
                            } else {
                                0L
                            }
                        }

                        if (!select) {
                            groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
                        }
                        try {
                            var targetIndex = if (desiredGroupId > 0L) newGroupList.indexOfFirst { it.id == desiredGroupId } else -1
                            if (targetIndex < 0) {
                                targetIndex = selectedGroupIndex.coerceIn(0, (newGroupList.size - 1).coerceAtLeast(0))
                            }
                            selectedGroupIndex = targetIndex

                            groupList = newGroupList
                            publishedGeneration = expectedGeneration
                            notifyDataSetChanged()

                            if (groupList.isNotEmpty() && targetIndex in groupList.indices) {
                                groupPager.setCurrentItem(targetIndex, false)
                                if (!select) {
                                    DataStore.selectedGroup = groupList[targetIndex].id
                                }
                            }

                            val hideTab = groupList.size < 2
                            tabLayout.isGone = hideTab
                            toolbar.elevation = 0F
                        } finally {
                            if (!select) {
                                groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                            }
                        }
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            tabLayout.post {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@post

                val currentPos = groupPager.currentItem
                val previousSelectedId = if (currentPos in groupList.indices) {
                    groupList[currentPos].id
                } else if (selectedGroupIndex in groupList.indices) {
                    groupList[selectedGroupIndex].id
                } else if (!select) {
                    DataStore.selectedGroup
                } else {
                    -1L
                }

                groupFragments.remove(groupId)
                groupList.removeAt(index)
                notifyItemRemoved(index)

                if (groupList.isEmpty()) {
                    reload()
                    return@post
                }

                val newPosition = if (previousSelectedId == groupId) {
                    index.coerceIn(0, groupList.size - 1)
                } else {
                    val existingIndex = groupList.indexOfFirst { it.id == previousSelectedId }
                    if (existingIndex >= 0) existingIndex else index.coerceIn(0, groupList.size - 1)
                }

                if (groupPager.currentItem != newPosition) {
                    groupPager.setCurrentItem(newPosition, false)
                }
                selectedGroupIndex = newPosition
                if (!select) {
                    DataStore.selectedGroup = groupList[newPosition].id
                }

                val hideTab = groupList.size < 2
                tabLayout.isGone = hideTab
                toolbar.elevation = 0F
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload(explicitTargetGroupId = profile.groupId)
            }
        }

        override suspend fun onUpdated(data: TrafficData) = Unit

        override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            onMainDispatcher { (activity as? MainActivity)?.refreshProfileAvailability() }
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {
        companion object {
            private val profileWrites = io.nekohasekai.sagernet.ui.state.OrderedWorkQueue(
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
            ) { Logs.w(it) }
        }
        private var viewVersion = 0L

        lateinit var proxyGroup: ProxyGroup
        var selected = false

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        var adapter: ConfigurationAdapter? = null

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
        }

        private val isEnabled: Boolean
            get() {
                return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
            }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView
        private var fabPaddingObserver: ViewTreeObserver? = null
        private var fabPaddingListener: ViewTreeObserver.OnGlobalLayoutListener? = null

        private fun reserveFabScrollSpace() {
            val host = activity as? MainActivity ?: return
            val list = configurationListView
            val fab = host.binding.fab
            val minimum = list.paddingBottom
            // Layout coordinates deliberately ignore translation/scale used by FAB hiding.
            // Both views share the activity root, so its system-inset padding cancels out.
            fun layoutTop(view: View): Int {
                var top = view.top
                var parent = view.parent as? View
                while (parent != null) {
                    top += parent.top - parent.scrollY
                    parent = parent.parent as? View
                }
                return top
            }
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                if (list.height > 0 && fab.height > 0) {
                    val overlap = layoutTop(list) + list.height - layoutTop(fab)
                    val padding = maxOf(minimum, overlap + dp2px(12))
                    if (list.paddingBottom != padding) {
                        list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, padding)
                    }
                }
            }
            fabPaddingListener = listener
            fabPaddingObserver = list.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
        }

        val select by lazy {
            try {
                (parentFragment as ConfigurationFragment).select
            } catch (e: Exception) {
                Logs.e(e)
                false
            }
        }
        val selectedItem by lazy {
            try {
                (parentFragment as ConfigurationFragment).selectedItem
            } catch (e: Exception) {
                Logs.e(e)
                null
            }
        }

        override fun onResume() {
            super.onResume()

            if (::configurationListView.isInitialized && configurationListView.size == 0) {
                configurationListView.adapter = adapter
                runOnDefaultDispatcher {
                    adapter?.reloadProfiles()
                }
            } else if (!::configurationListView.isInitialized) {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()
            configurationListView.requestFocus()
        }

        private var lastEmpty: Boolean? = null

        private fun updateEmptyState() {
            val root = view ?: return
            val owner = parentFragment as? ConfigurationFragment ?: return
            val empty = (adapter?.itemCount ?: 0) == 0
            root.findViewById<View>(R.id.empty_state).isVisible = empty
            configurationListView.isVisible = !empty
            val firstGroup = owner.adapter.groupList.size <= 1 && proxyGroup.ungrouped
            root.findViewById<android.widget.TextView>(R.id.empty_title).setText(
                if (select) R.string.ui_empty_select else if (firstGroup) R.string.ui_empty_title else R.string.ui_empty_group_title)
            root.findViewById<android.widget.TextView>(R.id.empty_body).setText(
                if (firstGroup || select) R.string.ui_empty_body else R.string.ui_empty_group_body)
            root.findViewById<View>(R.id.empty_add).apply {
                isVisible = !select
                setOnClickListener { owner.showAddNodeSheet() }
            }
            owner.updateGroupActions(proxyGroup, !empty)
            if (lastEmpty != empty) {
                lastEmpty = empty
                (activity as? MainActivity)?.refreshProfileAvailability()
            }
        }

        fun checkOrderMenu() {
            if (select) return

            val pf = requireParentFragment() as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }

                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }

                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            if (!::proxyGroup.isInitialized || adapter != null) return
            viewVersion++

            configurationListView = view.findViewById(R.id.configuration_list)
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            configurationListView.addItemDecoration(
                com.google.android.material.divider.MaterialDividerItemDecoration(
                    requireContext(), LinearLayout.VERTICAL
                ).apply {
                    dividerColor = requireContext().getColour(R.color.vialen_outline)
                    dividerThickness = dp2px(1)
                    dividerInsetStart = dp2px(4)
                    dividerInsetEnd = dp2px(4)
                }
            )
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter!!)
            GroupManager.addListener(adapter!!)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            if (!select) {
                reserveFabScrollSpace()
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter!!)

                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled) super.getDragDirs(recyclerView, viewHolder) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter?.move(
                            viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter?.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroyView() {
            viewVersion++
            fabPaddingListener?.let { listener ->
                fabPaddingObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
                if (::configurationListView.isInitialized) {
                    configurationListView.viewTreeObserver.takeIf { it.isAlive }
                        ?.removeOnGlobalLayoutListener(listener)
                }
            }
            fabPaddingObserver = null
            fabPaddingListener = null
            adapter?.let {
                ProfileManager.removeListener(it)
                GroupManager.removeListener(it)
            }

            if (::configurationListView.isInitialized) configurationListView.adapter = null
            adapter = null
            super.onDestroyView()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            private val expectedView = viewVersion
            private var loaded = false
            private val reloadGeneration = java.util.concurrent.atomic.AtomicLong()
            private fun alive() = expectedView == viewVersion && view != null
            private fun post(block: () -> Unit) {
                configurationListView.post { if (alive()) { block(); updateEmptyState() } }
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()
            private val contentSnapshots = HashMap<Long, List<Byte>>()
            private val pendingRemovals = HashSet<Long>()
            fun pendingRemovalIds(): List<Long> = pendingRemovals.toList()
            private fun snapshot(profile: ProxyEntity) = io.nekohasekai.sagernet.fmt.KryoConverters.serialize(profile).toList()

            private fun getItem(profileId: Long): ProxyEntity {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile!!
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    holder.bind(getItemAt(position))
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun onViewRecycled(holder: ConfigurationHolder) {
                holder.invalidate()
                super.onViewRecycled(holder)
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }

            private val updated = HashSet<ProxyEntity>()

            fun move(from: Int, to: Int) {
                if (from !in configurationIdList.indices || to !in configurationIdList.indices || from == to) return
                reloadGeneration.incrementAndGet()
                val range = minOf(from, to)..maxOf(from, to)
                val orders = range.map { getItemAt(it).userOrder }
                val moved = configurationIdList.removeAt(from)
                configurationIdList.add(to, moved)
                range.forEachIndexed { index, position ->
                    getItemAt(position).apply { userOrder = orders[index]; updated.add(this) }
                }
                notifyItemMoved(from, to)
            }

            fun commitMove() {
                val orders = updated.map { it.id to it.userOrder }
                updated.clear()
                if (orders.isEmpty()) return
                profileWrites.submit {
                    try { orders.forEach { (id, order) -> SagerDatabase.proxyDao.updateOrder(id, order) } }
                    catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        onMainDispatcher { if (alive()) (activity as? MainActivity)?.snackbar(error.readableMessage)?.show() }
                    }
                    reloadProfiles()
                }
            }

            fun remove(pos: Int) {
                if (pos !in configurationIdList.indices) return
                reloadGeneration.incrementAndGet()
                pendingRemovals.add(configurationIdList.removeAt(pos))
                notifyItemRemoved(pos)
                updateEmptyState()
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    post {
                        if (!pendingRemovals.remove(item.id) || item.id in configurationIdList) return@post
                        reloadGeneration.incrementAndGet()
                        configurationList[item.id] = item
                        val position = index.coerceIn(0, configurationIdList.size)
                        configurationIdList.add(position, item.id)
                        notifyItemInserted(position)
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                profileWrites.submit {
                    try {
                        for (entity in profiles) ProfileManager.deleteProfile(entity.groupId, entity.id)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        onMainDispatcher { if (alive()) (activity as? MainActivity)?.snackbar(error.readableMessage)?.show() }
                    } finally {
                        onMainDispatcher { profiles.forEach { pendingRemovals.remove(it.id) } }
                        reloadProfiles()
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    if (profile.id in configurationIdList || profile.id in pendingRemovals) return@post
                    reloadGeneration.incrementAndGet()
                    val pos = itemCount
                    contentSnapshots[profile.id] = snapshot(profile)
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
                if (profile.groupId != proxyGroup.id) return
                post {
                    val index = configurationIdList.indexOf(profile.id)
                    if (index < 0) return@post
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    reloadGeneration.incrementAndGet()
                    val oldProfile = configurationList[profile.id]
                    contentSnapshots[profile.id] = snapshot(profile)
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                    if (noTraffic && oldProfile != null) {
                        runOnDefaultDispatcher {
                            onUpdated(
                                TrafficData(
                                    id = profile.id,
                                    rx = oldProfile.rx,
                                    tx = oldProfile.tx
                                )
                            )
                        }
                    }
                }
            }

            override suspend fun onUpdated(data: TrafficData) {
                onMainDispatcher {
                    if (!alive()) return@onMainDispatcher
                    val index = configurationIdList.indexOf(data.id)
                    if (index < 0) return@onMainDispatcher
                    val holder = configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder
                    if (holder != null && holder.entity.id == data.id) holder.bind(holder.entity, data)
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return
                post {
                    val index = configurationIdList.indexOf(profileId)
                    if (index < 0) return@post
                    reloadGeneration.incrementAndGet()
                    configurationIdList.removeAt(index)
                    configurationList.remove(profileId)
                    contentSnapshots.remove(profileId)
                    notifyItemRemoved(index)
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId) ?: return
                // DNS progress only changes the group progress widget, not its profiles.
                if (groupId in GroupUpdater.updating) return
                reloadProfiles()
            }

            fun reloadProfiles() {
                val request = reloadGeneration.incrementAndGet()
                profileWrites.submit { reloadProfilesNow(request) }
            }

            private fun reloadProfilesNow(request: Long) {
                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }

                    }

                    GroupOrder.BY_DELAY -> {
                        newProfiles =
                            newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }
                }

                val newSnapshots = newProfiles.associate { it.id to snapshot(it) }
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                post {
                    if (!alive() || request != reloadGeneration.get()) return@post
                    val visibleProfiles = newProfiles.filter { it.id !in pendingRemovals }
                    val newProfileIds = visibleProfiles.map { it.id }
                    val oldIds = configurationIdList.toList()
                    val changedIds = visibleProfiles.filter { contentSnapshots[it.id] != newSnapshots[it.id] }.map { it.id }.toSet()
                    val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                        override fun getOldListSize() = oldIds.size
                        override fun getNewListSize() = newProfileIds.size
                        override fun areItemsTheSame(old: Int, new: Int) = oldIds[old] == newProfileIds[new]
                        override fun areContentsTheSame(old: Int, new: Int) = newProfileIds[new] !in changedIds
                    })
                    configurationList.clear()
                    configurationList.putAll(visibleProfiles.associateBy { it.id })
                    contentSnapshots.clear()
                    contentSnapshots.putAll(newSnapshots)
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    diff.dispatchUpdatesTo(this)
                    if (!loaded) {
                        val index = if (selected) newProfileIds.indexOf(selectedProxy) else -1
                        if (index >= 0) configurationListView.scrollTo(index, true)
                        loaded = true
                    }
                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val selectedView: LinearLayout = view.findViewById(R.id.selected_view)
            val profileCard = view as com.google.android.material.card.MaterialCardView
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val removeButton: ImageView = view.findViewById(R.id.remove)

            private val visualBinding = io.nekohasekai.sagernet.ui.state.BindingGeneration()
            fun invalidate() { visualBinding.next() }
            private var bindingViewVersion = viewVersion
            private fun valid(binding: Long) = visualBinding.accepts(binding) && bindingViewVersion == viewVersion && this@GroupFragment.view != null

            private fun updateCardBackground(selected: Boolean, expectedBinding: Long) {
                // Ignore queued colors from a previous bind or selection click.
                if (!valid(expectedBinding)) return
                profileCard.setCardBackgroundColor(requireContext().getColour(
                    if (selected) R.color.vialen_selected_background else R.color.vialen_surface
                ))
            }

            fun bind(proxyEntity: ProxyEntity, trafficData: TrafficData? = null) {
                val pf = parentFragment as? ConfigurationFragment ?: return

                entity = proxyEntity
                bindingViewVersion = viewVersion
                val binding = visualBinding.next()
                shareLayout.setOnClickListener(null)
                editButton.isEnabled = false
                removeButton.isEnabled = false
                selectedView.isInvisible = true
                trafficText.text = ""
                updateCardBackground(selectedItem?.id == proxyEntity.id, binding)

                if (select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else {
                    view.setOnClickListener {
                        val clickedBinding = visualBinding.next()
                        runOnDefaultDispatcher {
                            var update: Boolean
                            var lastSelected: Long
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                lastSelected = DataStore.selectedProxy
                                DataStore.selectedProxy = proxyEntity.id
                                onMainDispatcher {
                                    if (valid(clickedBinding)) bind(proxyEntity)
                                }
                            }

                            if (update) {
                                ProfileManager.postUpdate(lastSelected)
                                if (DataStore.serviceState.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (DataStore.serviceState.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()
                profileType.setTextColor(requireContext().getProtocolColor(proxyEntity.type))

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx
                if (trafficData != null) {
                    // use new data
                    tx = trafficData.tx
                    rx = trafficData.rx
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        Formatter.formatFileSize(view.context, tx),
                        Formatter.formatFileSize(view.context, rx)
                    )
                }

                var address = proxyEntity.displayAddress()
                if (showTraffic && address.length >= 30) {
                    address = address.substring(0, 27) + "..."
                }

                if (proxyEntity.requireBean().name.isBlank() || !pf.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone =
                    (!showTraffic || proxyEntity.status <= 0) && address.isBlank()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = getString(R.string.available, proxyEntity.ping)
                    profileStatus.setTextColor(requireContext().getColour(R.color.vialen_success))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.vialen_error))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    val err = proxyEntity.error ?: "<?>"
                    val msg = Protocols.genFriendlyMsg(err)
                    profileStatus.text = if (msg != err) msg else getString(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(err).tryToShow()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }

                editButton.setOnClickListener {
                    it.context.startActivity(
                        proxyEntity.settingIntent(
                            it.context, proxyGroup.type == GroupType.SUBSCRIPTION
                        )
                    )
                }

                removeButton.setOnClickListener {
                    adapter?.let {
                        val index = it.configurationIdList.indexOf(proxyEntity.id)
                        it.remove(index)
                        undoManager.remove(index to proxyEntity)
                    }
                }

                val selectOrChain = select || proxyEntity.type == ProxyEntity.TYPE_CHAIN
                shareLayout.isGone = selectOrChain
                editButton.isGone = select
                removeButton.isGone = select

                runOnDefaultDispatcher {
                    val selected = (selectedItem?.id ?: DataStore.selectedProxy) == proxyEntity.id
                    val started =
                        selected && DataStore.serviceState.started && DataStore.currentProfile == proxyEntity.id
                    onMainDispatcher {
                        if (!valid(binding)) return@onMainDispatcher
                        editButton.isEnabled = !started
                        removeButton.isEnabled = !started
                        selectedView.visibility = if (selected) View.VISIBLE else View.INVISIBLE
                        updateCardBackground(selected, binding)
                    }

                    fun showShare(anchor: View) {
                        val popup = PopupMenu(requireContext(), anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        when {
                            !proxyEntity.haveStandardLink() -> {
                                popup.menu.findItem(R.id.action_group_qr).subMenu?.removeItem(R.id.action_standard_qr)
                                popup.menu.findItem(R.id.action_group_clipboard).subMenu?.removeItem(
                                    R.id.action_standard_clipboard
                                )
                            }

                            !proxyEntity.haveLink() -> {
                                popup.menu.removeItem(R.id.action_group_qr)
                                popup.menu.removeItem(R.id.action_group_clipboard)
                            }
                        }

                        popup.setOnMenuItemClickListener { item ->
                            if (valid(binding)) onMenuItemClick(item) else true
                        }
                        popup.show()
                    }

                    if (!(select || proxyEntity.type == ProxyEntity.TYPE_CHAIN)) {
                        onMainDispatcher {
                            if (!valid(binding)) return@onMainDispatcher
                            shareLayer.setBackgroundColor(Color.TRANSPARENT)
                            shareButton.setImageResource(R.drawable.ic_social_share)
                            shareButton.clearColorFilter()
                            shareButton.isVisible = true

                            shareLayout.setOnClickListener {
                                if (valid(binding)) showShare(it)
                            }
                        }
                    }
                }

            }

            var currentName = ""
            fun showCode(link: String) {
                QRCodeDialog(link, currentName).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    currentName = entity.displayName()!!
                    when (item.itemId) {
                        R.id.action_standard_qr -> showCode(entity.toStdLink())
                        R.id.action_standard_clipboard -> export(entity.toStdLink())
                        R.id.action_universal_qr -> showCode(entity.requireBean().toUniversalLink())
                        R.id.action_universal_clipboard -> export(
                            entity.requireBean().toUniversalLink()
                        )

                        R.id.action_config_export_clipboard -> export(entity.exportConfig().first)
                        R.id.action_config_export_file -> {
                            val cfg = entity.exportConfig()
                            val owner = parentFragment as ConfigurationFragment
                            owner.pendingExportProfileId = entity.id
                            if (!startFilesForResult(owner.exportConfig, cfg.second)) {
                                owner.pendingExportProfileId = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

    }

    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            val profileId = pendingExportProfileId
            pendingExportProfileId = null
            if (data != null) {
                val resolver = app.contentResolver
                runOnDefaultDispatcher {
                    try {
                        requireNotNull(profileId) { app.getString(R.string.profile_export_target_missing) }
                        val profile = checkNotNull(SagerDatabase.proxyDao.getById(profileId)) {
                            app.getString(R.string.profile_export_target_missing)
                        }
                        val config = profile.exportConfig().first
                        writeExportConfig(
                            { resolver.openOutputStream(data) },
                            config,
                            app.getString(R.string.action_export_err)
                        )
                        showMessage(app.getString(R.string.action_export_msg))
                    } catch (e: Exception) {
                        Logs.w(e)
                        showMessage(e.readableMessage)
                    }

                }
            }
        }

    companion object {
        @VisibleForTesting
        internal fun writeExportConfig(
            streamOpener: () -> OutputStream?,
            config: String,
            errorMessage: String
        ) {
            try {
                val out = streamOpener() ?: throw IOException()
                out.bufferedWriter().use {
                    it.write(config)
                    it.flush()
                }
            } catch (e: Exception) {
                Logs.w(e)
                throw IOException(errorMessage, e)
            }
        }
    }

}
