package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutEmptyRouteBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.state.OrderedWorkQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.UndoSnackbarManager

class RouteFragment : ToolbarFragment(R.layout.layout_route), Toolbar.OnMenuItemClickListener {

    companion object {
        // Business writes survive view recreation and retain event order across adapters.
        private val writes = OrderedWorkQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { Logs.w(it) }
    }

    private var resetDialog: androidx.appcompat.app.AlertDialog? = null
    lateinit var activity: MainActivity
    lateinit var ruleListView: RecyclerView
    lateinit var ruleAdapter: RuleAdapter
    lateinit var undoManager: UndoSnackbarManager<RuleEntity>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_route)
        toolbar.inflateMenu(R.menu.add_route_menu)
        toolbar.setOnMenuItemClickListener(this)

        ruleListView = view.findViewById(R.id.route_list)
        ruleListView.layoutManager = FixedLinearLayoutManager(ruleListView)
        ruleAdapter = RuleAdapter()
        ProfileManager.addListener(ruleAdapter)
        ruleListView.adapter = ruleAdapter
        undoManager = UndoSnackbarManager(activity, ruleAdapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is RuleAdapter.DocumentHolder) {
                0
            } else {
                super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                if (index - 1 !in ruleAdapter.ruleList.indices) return
                ruleAdapter.remove(index)
                undoManager.remove(index to (viewHolder as RuleAdapter.RuleHolder).rule)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target is RuleAdapter.DocumentHolder) {
                    false
                } else {
                    ruleAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    true
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                ruleAdapter.commitMove()
            }
        }).attachToRecyclerView(ruleListView)
    }

    override fun onDestroyView() {
        resetDialog?.dismiss()
        resetDialog = null
        if (::ruleAdapter.isInitialized) {
            ProfileManager.removeListener(ruleAdapter)
        }
        if (::undoManager.isInitialized) undoManager.flush()
        ruleAdapter.active = false
        ruleListView.adapter = null
        super.onDestroyView()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_route -> {
                startActivity(Intent(context, RouteSettingsActivity::class.java))
            }
            R.id.action_reset_route -> {
                resetDialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        undoManager.invalidate()
                        val target = ruleAdapter
                        target.version++
                        target.updated.clear()
                        target.pendingEnabled.clear()
                        target.pendingRemovals.clear()
                        target.ruleList.clear()
                        target.notifyDataSetChanged()
                        target.submitMutation {
                            SagerDatabase.rulesDao.reset()
                            DataStore.rulesFirstCreate = false
                            target.reload()
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
            R.id.action_manage_assets -> {
                startActivity(Intent(requireContext(), AssetsActivity::class.java))
            }
        }
        return true
    }

    inner class RuleAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ProfileManager.RuleListener, UndoSnackbarManager.Interface<RuleEntity> {

        var active = true
        @Volatile var version = 0L
        val ruleList = ArrayList<RuleEntity>()
        val pendingEnabled = HashMap<Long, Pair<Long, Boolean>>()
        val pendingRemovals = HashSet<Long>()
        private var mutation = 0L

        fun submitMutation(operation: suspend () -> Unit) {
            val epoch = version
            writes.submit {
                try {
                    operation()
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logs.w(error)
                    // A failed reset, delete or move must recover from the authoritative database.
                    if (epoch == version) {
                        try { reload() } catch (reloadError: Exception) { Logs.w(reloadError) }
                        onMainDispatcher { if (active) snackbar(error.readableMessage).show() }
                    }
                }
            }
        }
        private fun post(block: () -> Unit) {
            val expected = version
            ruleListView.post { if (active && expected == version) block() }
        }
        suspend fun reload() {
            val expected = version
            val rules = ProfileManager.getRules()
            post {
                if (expected != version) return@post
                ruleList.clear()
                ruleList.addAll(rules.filter { it.id !in pendingRemovals }.map { rule ->
                    pendingEnabled[rule.id]?.let { rule.copy(enabled = it.second) } ?: rule
                })
                notifyDataSetChanged()
            }
        }

        init {
            setHasStableIds(true)
            val initialEpoch = version
            writes.submit { if (initialEpoch == version) reload() }
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                DocumentHolder(LayoutEmptyRouteBinding.inflate(layoutInflater, parent, false))
            } else {
                RuleHolder(LayoutRouteItemBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun getItemViewType(position: Int): Int {
            if (position == 0) return 0
            return 1
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is DocumentHolder) {
                holder.bind()
            } else if (holder is RuleHolder) {
                holder.bind(ruleList[position - 1])
            }
        }

        override fun getItemCount(): Int {
            return ruleList.size + 1
        }

        override fun getItemId(position: Int): Long {
            if (position == 0) return 0L
            return ruleList[position - 1].id
        }

        val updated = HashSet<RuleEntity>()
        fun move(from: Int, to: Int) {
            val source = from - 1
            val target = to - 1
            if (source !in ruleList.indices || target !in ruleList.indices || source == target) return
            val range = minOf(source, target)..maxOf(source, target)
            val orders = range.map { ruleList[it].userOrder }
            val moved = ruleList.removeAt(source)
            ruleList.add(target, moved)
            range.forEachIndexed { index, position ->
                ruleList[position].userOrder = orders[index]
                updated.add(ruleList[position])
            }
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            val orders = updated.map { it.id to it.userOrder }
            updated.clear()
            if (orders.isEmpty()) return
            submitMutation {
                orders.forEach { (id, order) -> SagerDatabase.rulesDao.updateOrder(id, order) }
                onMainDispatcher { if (active) needReload() }
            }
        }

        fun remove(index: Int) {
            if (index - 1 !in ruleList.indices) return
            pendingRemovals.add(ruleList.removeAt(index - 1).id)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, RuleEntity>>) {
            if (!active) return
            for ((index, item) in actions) {
                if (!pendingRemovals.remove(item.id) || ruleList.any { it.id == item.id }) continue
                val position = (index - 1).coerceIn(0, ruleList.size)
                ruleList.add(position, item)
                notifyItemInserted(position + 1)
            }
        }

        override fun commit(actions: List<Pair<Int, RuleEntity>>) {
            val rules = actions.map { it.second }
            submitMutation {
                try { ProfileManager.deleteRules(rules) }
                finally { onMainDispatcher { rules.forEach { pendingRemovals.remove(it.id) } } }
            }
        }

        override suspend fun onAdd(rule: RuleEntity) {
            post {
                if (rule.id in pendingRemovals || ruleList.any { it.id == rule.id }) return@post
                ruleList.add(rule)
                notifyItemInserted(ruleList.size)
                needReload()
            }
        }

        override suspend fun onUpdated(rule: RuleEntity) {
            post {
                val index = ruleList.indexOfFirst { it.id == rule.id }
                if (index < 0) return@post
                ruleList[index] = pendingEnabled[rule.id]?.let { rule.copy(enabled = it.second) } ?: rule
                notifyItemChanged(index + 1)
                needReload()
            }
        }

        override suspend fun onRemoved(ruleId: Long) {
            post {
                val index = ruleList.indexOfFirst { it.id == ruleId }
                if (index >= 0) {
                    ruleList.removeAt(index)
                    notifyItemRemoved(index + 1)
                }
                needReload()
            }
        }

        override suspend fun onCleared() {
            post {
                ruleList.clear()
                notifyDataSetChanged()
                needReload()
            }
        }

        inner class DocumentHolder(binding: LayoutEmptyRouteBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind() {
                itemView.setOnClickListener {
                    MaterialAlertDialogBuilder(it.context)
                        .setTitle(R.string.vialen_route_help_title)
                        .setMessage(R.string.vialen_route_help_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

        inner class RuleHolder(binding: LayoutRouteItemBinding) : RecyclerView.ViewHolder(binding.root) {

            lateinit var rule: RuleEntity
            val profileName = binding.profileName
            val profileType = binding.profileType
            val routeOutbound = binding.routeOutbound
            val editButton = binding.edit
            val shareLayout = binding.share
            val enableSwitch = binding.enable

            fun bind(ruleEntity: RuleEntity) {
                rule = ruleEntity
                profileName.text = rule.displayName()
                profileType.text = rule.mkSummary()
                routeOutbound.text = "→ ${rule.displayOutbound()} · ${getString(if (rule.enabled) R.string.ui_enabled else R.string.ui_disabled)}"
                itemView.setOnClickListener {
                    enableSwitch.performClick()
                }
                enableSwitch.contentDescription = getString(R.string.rule_enable_named, ruleEntity.displayName())
                enableSwitch.setOnCheckedChangeListener(null)
                enableSwitch.isChecked = ruleEntity.enabled
                enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    val id = ruleEntity.id
                    val enabled = isChecked
                    val ticket = ++mutation
                    val epoch = version
                    pendingEnabled[id] = ticket to enabled
                    ruleEntity.enabled = enabled
                    writes.submit {
                        try {
                            val changed = SagerDatabase.rulesDao.updateEnabled(id, enabled)
                            onMainDispatcher {
                                if (epoch != version || pendingEnabled[id]?.first != ticket) return@onMainDispatcher
                                pendingEnabled.remove(id)
                                if (active) {
                                    val index = ruleList.indexOfFirst { it.id == id }
                                    if (index >= 0) {
                                        if (changed == 0) {
                                            ruleList.removeAt(index)
                                            notifyItemRemoved(index + 1)
                                        } else {
                                            ruleList[index].enabled = enabled
                                            notifyItemChanged(index + 1)
                                        }
                                    }
                                    needReload()
                                }
                            }
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            Logs.w(error)
                            val latest = onMainDispatcher {
                                if (epoch == version && pendingEnabled[id]?.first == ticket) {
                                    pendingEnabled.remove(id)
                                    true
                                } else false
                            }
                            if (latest) {
                                try { reload() } catch (reloadError: Exception) { Logs.w(reloadError) }
                            }
                            onMainDispatcher { if (active) snackbar(error.readableMessage).show() }
                        }
                    }
                }
                editButton.setOnClickListener {
                    startActivity(Intent(it.context, RouteSettingsActivity::class.java).apply {
                        putExtra(RouteSettingsActivity.EXTRA_ROUTE_ID, ruleEntity.id)
                    })
                }
            }
        }

    }

}