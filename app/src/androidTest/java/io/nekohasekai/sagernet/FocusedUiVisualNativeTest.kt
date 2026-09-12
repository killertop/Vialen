package io.nekohasekai.sagernet

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.RouteSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.ScannerActivity
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import moe.matsuri.nb4a.TempDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Small, bounded capture. Does not use ActivityScenario.close or waitForIdleSync. */
@RunWith(AndroidJUnit4::class)
class FocusedUiVisualNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val handler = Handler(Looper.getMainLooper())
    private val owned = CopyOnWriteArrayList<Activity>()
    private val destroyed = ConcurrentHashMap<Activity, CountDownLatch>()
    private val checks = JSONArray()
    private val screenshots = JSONArray()
    private val failures = mutableListOf<String>()
    private lateinit var output: File
    private var label = ""
    private var roomIsolated = false
    private var fixtureGroup = 0L
    private var fixtureFormGroup = 0L
    private var fixtureProfile = 0L
    private var fixtureRule = 0L
    private val roomTables = listOf("proxy_groups", "proxy_entities", "rules")
    private fun backupName(table: String) = "vialen_visual_backup_$table"
    private val launchTarget = AtomicReference<Class<out Activity>?>()
    private val launchResult = AtomicReference<Activity?>()
    private var launchReady = CountDownLatch(1)
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            owned.add(activity)
            destroyed[activity] = CountDownLatch(1)
            // Render the camera controls without permitting ambient QR import during capture.
            if (activity is ScannerActivity) activity.finished.set(true)
        }
        override fun onActivityResumed(activity: Activity) {
            if (launchTarget.get()?.isInstance(activity) == true) {
                launchResult.set(activity)
                launchReady.countDown()
            }
        }
        override fun onActivityDestroyed(activity: Activity) { destroyed[activity]?.countDown() }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }

    @Test fun captureFocusedUi() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires physical-device opt in: -e vialenFocusedVisual true",
            args.getString("vialenFocusedVisual") == "true")
        val mode = args.getString("vialenVisualMode") ?: "light"
        require(mode == "light") { "Vialen supports only light appearance" }
        label = (args.getString("vialenVisualLabel") ?: mode).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        check(DataStore.serviceState == BaseService.State.Stopped || DataStore.serviceState == BaseService.State.Idle)
        main {
            val existing = Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
            }.filter { it.packageName == context.packageName }
            check(existing.isEmpty()) { "Close existing app Activities before focused capture" }
        }
        output = File(checkNotNull(context.getExternalFilesDir(null)), "ui-modernization/${System.currentTimeMillis()}-$label")
        check(output.mkdirs())
        val publicBefore = snapshot(PublicDatabase.kvPairDao)
        val cacheBefore = snapshot(TempDatabase.profileCacheDao)
        val application = context.applicationContext as Application
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            // Clear only after exact snapshots, before any Activity can render a saved endpoint.
            PublicDatabase.kvPairDao.reset()
            TempDatabase.profileCacheDao.reset()
            installFixtures()
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            surface("main-surfaces") { captureMainSurfaces() }
            surface("group-form") { captureForm("group-form", GroupSettingsActivity::class.java, GroupSettingsActivity.EXTRA_GROUP_ID, fixtureFormGroup) }
            surface("rule-form") { captureForm("rule-form", RouteSettingsActivity::class.java, RouteSettingsActivity.EXTRA_ROUTE_ID, fixtureRule) }
            surface("profile-form") { captureForm("profile-form", SocksSettingsActivity::class.java, ProfileSettingsActivity.EXTRA_PROFILE_ID, fixtureProfile) }
            surface("editor") { captureEditor() }
            surface("scanner") { captureScanner() }
        } finally {
            // Finish only Activities created while this test owned the lifecycle callback.
            // Each wait is bounded; a broken teardown cannot turn into an infinite capture loop.
            owned.filter { destroyed[it]?.count != 0L }.forEach { activity ->
                try { close(activity) } catch (error: Throwable) { failures.add("cleanup ${activity.javaClass.simpleName}: ${error.javaClass.simpleName}") }
            }
            application.unregisterActivityLifecycleCallbacks(callbacks)
            try { restoreRoom() } catch (error: Throwable) { failures.add("Room restore: ${error.javaClass.simpleName}; internal backup tables retained") }
            try { restore(PublicDatabase.kvPairDao, publicBefore) } catch (error: Throwable) { failures.add("public config restore: ${error.javaClass.simpleName}") }
            try { restore(TempDatabase.profileCacheDao, cacheBefore) } catch (error: Throwable) { failures.add("draft restore: ${error.javaClass.simpleName}") }
            writeIndex("FINISHED")
            println("VIALEN_FOCUSED_VISUAL ${output.absolutePath} screenshots=${screenshots.length()} failures=${failures.size}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun installFixtures() {
        check(GroupUpdater.updating.isEmpty()) { "Wait for active subscription updates before capture" }
        val database = SagerDatabase.instance
        database.runInTransaction {
            val sql = database.openHelper.writableDatabase
            val allBackups = roomTables.map { backupName(it) } + backupName("sequence")
            allBackups.forEach { table ->
                sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                    check(!it.moveToFirst()) { "Previous visual backup exists; restore it before another capture" }
                }
            }
            roomTables.forEach { table ->
                sql.execSQL("CREATE TABLE ${backupName(table)} AS SELECT * FROM $table")
            }
            sql.execSQL("CREATE TABLE ${backupName("sequence")} AS SELECT * FROM sqlite_sequence WHERE name IN ('proxy_groups','proxy_entities','rules')")
            SagerDatabase.proxyDao.reset()
            SagerDatabase.groupDao.reset()
            SagerDatabase.rulesDao.reset()
            fixtureGroup = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "示例节点 · Local examples", userOrder = 1, ungrouped = true))
            fixtureFormGroup = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "旅行配置 · Travel examples", userOrder = 2))
            val subscription = SubscriptionBean().apply {
                initializeDefaultValues()
                link = "https://example.invalid/never-fetch"
                autoUpdate = false
                subscriptionUserinfo = "upload=104857600; download=524288000; total=10737418240"
            }
            SagerDatabase.groupDao.createGroup(ProxyGroup(name = "订阅示例 · Offline sample", userOrder = 3,
                type = GroupType.SUBSCRIPTION, subscription = subscription))
            (1..8).forEach { index ->
                val bean = SOCKSBean().apply {
                    initializeDefaultValues()
                    name = when (index) { 1 -> "本地示例 · Primary node"; 2 -> "备用示例 · Backup node"; else -> "示例节点 $index · Example" }
                    serverAddress = "127.0.0.1"
                    serverPort = 10000 + index
                }
                val id = SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = fixtureGroup, userOrder = index.toLong(),status = if (index == 2) 1 else 0, ping = if (index == 2) 86 else 0).putBean(bean))
                if (index == 1) fixtureProfile = id
            }
            (1..6).forEach { index ->
                val id = SagerDatabase.rulesDao.createRule(RuleEntity(name = "示例规则 $index · Example rule",
                    userOrder = index.toLong(), enabled = index % 2 == 0, domains = "example$index.invalid", outbound = if (index % 2 == 0) -1 else 0))
                if (index == 1) fixtureRule = id
            }
        }
        roomIsolated = true
        DataStore.selectedGroup = fixtureGroup
        DataStore.selectedProxy = fixtureProfile
        DataStore.rulesFirstCreate = true
    }

    private fun restoreRoom() {
        if (!roomIsolated) return
        SagerDatabase.instance.runInTransaction {
            val sql = SagerDatabase.instance.openHelper.writableDatabase
            roomTables.reversed().forEach { sql.execSQL("DELETE FROM $it") }
            roomTables.forEach { table ->
                sql.execSQL("INSERT INTO $table SELECT * FROM ${backupName(table)}")
                for ((first, second) in listOf(table to backupName(table), backupName(table) to table)) {
                    sql.query("SELECT COUNT(*) FROM (SELECT * FROM $first EXCEPT SELECT * FROM $second)").use {
                        check(it.moveToFirst() && it.getLong(0) == 0L) { "Room snapshot mismatch" }
                    }
                }
            }
            sql.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('proxy_groups','proxy_entities','rules')")
            sql.execSQL("INSERT INTO sqlite_sequence SELECT * FROM ${backupName("sequence")}")
            roomTables.forEach { sql.execSQL("DROP TABLE ${backupName(it)}") }
            sql.execSQL("DROP TABLE ${backupName("sequence")}")
        }
        roomIsolated = false
        record("room-restore", "PASS", JSONObject().put("meaning", "All original row values/BLOBs and autoincrement counters restored"))
    }

    private fun captureMainSurfaces() {
        val activity = launch(MainActivity::class.java)
        try {
            await("fixture nodes") {
                val page = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment
                page?.getCurrentGroupFragment()?.adapter?.itemCount == 8
            }
            val nodeList = awaitFixtureRows("nodes", activity, "本地示例 · Primary node")
            main {
                appbar("nodes", activity)
                contract("nodes-safe-fixture", (activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as ConfigurationFragment)
                    .getCurrentGroupFragment()?.proxyGroup?.id == fixtureGroup)
                contract("nodes-fab-label", !activity.binding.fab.contentDescription.isNullOrBlank())
                contract("nodes-progress-hidden", activity.binding.fabProgress.visibility != View.VISIBLE)
            }
            shot("nodes", activity)
            main { nodeList.scrollToPosition(7) }
            awaitFixtureRows("nodes-bottom", activity, "示例节点 8 · Example", nodeList)
            main { nodeList.scrollBy(0, nodeList.height) }
            awaitFixtureRows("nodes-bottom-settled", activity, "示例节点 8 · Example", nodeList)
            main {
                val row = checkNotNull(nodeList.findViewHolderForAdapterPosition(7)).itemView
                val visible = Rect()
                val fullyVisible = row.getGlobalVisibleRect(visible) && visible.height() >= row.height - 1
                contract("nodes-last-row-above-fab", fullyVisible && bounds(row).bottom <= bounds(activity.binding.fab).top,
                    JSONObject().put("row", rectJson(bounds(row))).put("fab", rectJson(bounds(activity.binding.fab))))
            }
            shot("nodes-bottom", activity)
            main { navigate(activity, R.id.nav_group) }
            await("fixture groups") {
                (activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? GroupFragment)?.groupAdapter?.itemCount == 3
            }
            awaitFixtureRows("groups", activity, "旅行配置 · Travel examples")
            main {
                appbar("groups", activity)
                hiddenConnectionControls("groups", activity)
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment
                val actions = descendants(fragment.requireView()).filter { it.isShown && it.isClickable && it.id in listOf(R.id.edit, R.id.options) }
                contract("group-actions-labeled", actions.isNotEmpty() && actions.all { !it.contentDescription.isNullOrBlank() })
                contract("groups-no-update-progress", descendants(fragment.requireView()).filter { it.id == R.id.subscription_update_progress }.none { it.visibility == View.VISIBLE })
            }
            shot("groups", activity)
            main { navigate(activity, R.id.nav_route) }
            await("fixture rules") {
                (activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? RouteFragment)?.ruleAdapter?.itemCount == 7
            }
            awaitFixtureRows("rules", activity, "示例规则 1 · Example rule")
            main {
                appbar("rules", activity)
                hiddenConnectionControls("rules", activity)
                val switches = descendants(activity.window.decorView).filter { it.id == R.id.enable && it.isShown }
                contract("rule-switches-labeled", switches.isNotEmpty() && switches.all { !it.contentDescription.isNullOrBlank() })
            }
            shot("rules", activity)
            main { navigate(activity, R.id.nav_settings) }
            await("settings content") {
                activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) is SettingsFragment &&
                    descendants(activity.window.decorView).filterIsInstance<RecyclerView>().any { it.isShown && it.childCount > 0 }
            }
            main { appbar("settings", activity); hiddenConnectionControls("settings", activity) }
            shot("settings", activity)
            captureListBottom("settings-bottom", activity)
        } catch (error: Throwable) {
            // Capture before finally closes the activity; never substitute another foreground window.
            try {
                if (main { !activity.isDestroyed && activity.window.decorView.hasWindowFocus() }) {
                    shot("main-surfaces-failure", activity)
                }
            } catch (captureError: Throwable) { error.addSuppressed(captureError) }
            throw error
        } finally { close(activity) }
    }

    private fun navigate(activity: MainActivity, id: Int) {
        activity.displayFragmentWithId(id)
        activity.supportFragmentManager.executePendingTransactions()
    }

    /** Adapter counts can precede binding and item/fragment fade-in animations. */
    private fun awaitFixtureRows(name: String, activity: Activity, fixture: String, diagnosticList: RecyclerView? = null): RecyclerView {
        var ready: RecyclerView? = null
        var stablePolls = 0
        var previousBounds: List<Rect>? = null
        try { await("$name fixture rendered") {
            val text = descendants(activity.window.decorView).filterIsInstance<TextView>()
                .firstOrNull { it.isShown && it.text.toString() == fixture }
            // ViewPager2 has an outer RecyclerView too. Scroll the fixture row's nearest
            // owning list, rather than treating the pager's group positions as node positions.
            val list = generateSequence(text?.parent as? View) { it.parent as? View }
                .filterIsInstance<RecyclerView>().firstOrNull()
            val rows = list?.let { (0 until it.childCount).map(it::getChildAt) }
                .orEmpty().filter { it.getGlobalVisibleRect(Rect()) }
            val currentBounds = rows.map(::bounds)
            val settled = list != null && text != null && visiblyOpaque(text) && rows.isNotEmpty() &&
                rows.all { it.isLaidOut && !it.isLayoutRequested && visiblyOpaque(it) } &&
                !list.isLayoutRequested && !list.isComputingLayout && !list.hasPendingAdapterUpdates() &&
                list.scrollState == RecyclerView.SCROLL_STATE_IDLE && list.itemAnimator?.isRunning != true
            stablePolls = if (settled && previousBounds == currentBounds) stablePolls + 1 else 0
            previousBounds = currentBounds
            ready = list
            settled && stablePolls >= 3
        } } catch (error: Throwable) {
            try { main {
                fun viewState(view: View): JSONObject {
                    val visible = Rect()
                    val hasVisibleRect = view.getGlobalVisibleRect(visible)
                    return JSONObject().put("shown", view.isShown).put("alpha", view.alpha.toDouble())
                        .put("bounds", rectJson(bounds(view))).put("hasVisibleRect", hasVisibleRect)
                        .put("visibleRect", rectJson(visible)).put("opaque", visiblyOpaque(view))
                }
                val list = diagnosticList ?: ready
                val rows = JSONArray()
                list?.let { recycler ->
                    for (i in 0 until recycler.childCount) {
                        val row = recycler.getChildAt(i)
                        rows.put(viewState(row).put("adapterPosition", recycler.getChildAdapterPosition(row)))
                    }
                }
                val matches = JSONArray()
                descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .filter { it.text.toString() == fixture }.forEach { matches.put(viewState(it)) }
                record("$name-timeout-diagnostic", "INFO", JSONObject()
                    .put("adapterClass", list?.adapter?.javaClass?.name ?: JSONObject.NULL)
                    .put("itemCount", list?.adapter?.itemCount ?: -1)
                    .put("scrollState", list?.scrollState ?: -1)
                    .put("layoutRequested", list?.isLayoutRequested ?: false)
                    .put("computingLayout", list?.isComputingLayout ?: false)
                    .put("pendingAdapterUpdates", list?.hasPendingAdapterUpdates() ?: false)
                    .put("animatorRunning", list?.itemAnimator?.isRunning ?: false)
                    .put("attachedRows", rows).put("matchingTextViews", matches))
            } } catch (diagnosticError: Throwable) { error.addSuppressed(diagnosticError) }
            throw error
        }
        main { contract("$name-fixture-visible", ready != null) }
        return checkNotNull(ready)
    }

    private fun visiblyOpaque(view: View): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0 || !view.getGlobalVisibleRect(Rect())) return false
        var current: View? = view
        while (current != null) {
            if (current.alpha < 0.99f) return false
            current = current.parent as? View
        }
        return true
    }

    private fun hiddenConnectionControls(name: String, activity: MainActivity) {
        // GONE may be reached at the end of FAB's short transition; page policy itself is immediate.
        contract("$name-controls-policy", !activity.binding.stats.allowShow)
        contract("$name-progress-hidden", activity.binding.fabProgress.visibility != View.VISIBLE)
    }

    private fun <T : FragmentActivity> captureForm(name: String, type: Class<T>, extra: String, id: Long) {
        val activity = launch(type, Bundle().apply { putLong(extra, id) })
        try {
            await("$name preferences") {
                activity.supportFragmentManager.findFragmentById(R.id.settings)?.view != null &&
                    descendants(activity.window.decorView).filterIsInstance<RecyclerView>().any { it.childCount > 0 }
            }
            main {
                appbar(name, activity)
                contract("$name-draft-clean", !DataStore.dirty)
                contract("$name-apply-present", activity.findViewById<Toolbar>(R.id.toolbar).menu.findItem(R.id.action_apply) != null)
            }
            shot(name, activity)
            captureListBottom("$name-bottom", activity)
        } finally { close(activity) }
    }

    private fun captureListBottom(name: String, activity: Activity) {
        val list = main { descendants(activity.window.decorView).filterIsInstance<RecyclerView>().firstOrNull { it.isShown && (it.adapter?.itemCount ?: 0) > 0 } } ?: return
        val last = main { (list.adapter?.itemCount ?: 1) - 1 }
        if (!main { list.canScrollVertically(1) }) return
        main { list.scrollToPosition(last) }
        await("$name bound") { list.findViewHolderForAdapterPosition(last)?.itemView?.isShown == true && !list.isLayoutRequested }
        shot(name, activity)
    }

    private fun appbar(name: String, activity: Activity) {
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        val density = activity.resources.displayMetrics.density
        contract("$name-toolbar-height", toolbar.height / density >= 48f - 0.5f,
            JSONObject().put("heightDp", toolbar.height / density.toDouble()))
        val controls = descendants(toolbar).filter { it.isShown && it.isClickable && it !is ViewGroup && it.width > 0 }
        contract("$name-toolbar-labels", controls.isNotEmpty() && controls.all {
            !it.contentDescription.isNullOrBlank() || (it is TextView && it.text.isNotBlank())
        })
        safeSides("$name-toolbar-cutout", activity, controls)
    }

    private fun captureEditor() {
        DataStore.serverConfig = "{\n  \"outbounds\": [\n    {\"type\": \"direct\", \"tag\": \"Example 示例\"}\n  ]\n}"
        val activity = launch(ConfigEditActivity::class.java)
        try {
            val keyboard = main { activity.findViewById<RecyclerView>(R.id.extended_keyboard) }
            await("symbol keys bound") { keyboard.adapter?.itemCount == 6 && keyboard.childCount > 0 }
            val iconIds = listOf(R.id.action_tab, R.id.action_undo, R.id.action_redo, R.id.action_format)
            main {
                val icons = iconIds.map { activity.findViewById<View>(it) }
                icons.forEach { target("editor-icon-${it.resources.getResourceEntryName(it.id)}", it) }
                nonOverlap("editor-icon-overlap", icons)
                safeSides("editor-cutout-safety", activity, icons)
            }
            // Check all six actual RecyclerView cells, including horizontally off-screen keys.
            for (position in 0 until 6) {
                main { keyboard.scrollToPosition(position) }
                await("symbol $position laid out") {
                    val cell = keyboard.findViewHolderForAdapterPosition(position)?.itemView
                    val visible = Rect()
                    cell != null && !keyboard.isLayoutRequested && cell.getGlobalVisibleRect(visible) &&
                        visible.width() >= minOf(cell.width, keyboard.width) - 1
                }
                main {
                    val key = checkNotNull(keyboard.findViewHolderForAdapterPosition(position)).itemView
                    target("editor-symbol-$position", key, requireFullyVisible = false)
                    nonOverlap("editor-symbol-$position-overlap", iconIds.map { activity.findViewById<View>(it) } + key)
                }
            }
            main { keyboard.scrollToPosition(0) }
            main {
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            var previousInitialInsets: Pair<Int, Int>? = null
            var initialStableSince = android.os.SystemClock.uptimeMillis()
            await("editor initial IME hidden and insets settled") {
                val decor = activity.window.decorView
                val insets = ViewCompat.getRootWindowInsets(decor)
                val current = insets?.let {
                    it.getInsets(WindowInsetsCompat.Type.ime()).bottom to
                        it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                }
                val ready = insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                    current?.first == 0 && decor.hasWindowFocus() &&
                    !activity.findViewById<View>(R.id.keyboard_container).isLayoutRequested
                if (!ready || current != previousInitialInsets) {
                    previousInitialInsets = current
                    initialStableSince = android.os.SystemClock.uptimeMillis()
                }
                ready && android.os.SystemClock.uptimeMillis() - initialStableSince >= 350
            }
            main {
                val decor = activity.window.decorView
                val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                record("editor-initial-window-state", "INFO", JSONObject()
                    .put("lightNavigationBars", WindowCompat.getInsetsController(activity.window, decor).isAppearanceLightNavigationBars)
                    .put("imeVisible", insets.isVisible(WindowInsetsCompat.Type.ime()))
                    .put("imeBottomPx", insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                    .put("navigationBottomPx", insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                    .put("windowFocus", decor.hasWindowFocus()))
            }
            shot("editor", activity)
            main {
                activity.binding.editor.requestFocus()
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(activity.binding.editor, InputMethodManager.SHOW_IMPLICIT)
            }
            val imeShown = await("IME visible", timeoutMs = 6000, required = false) {
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            if (!imeShown) {
                record("editor-ime", "UNVERIFIED", JSONObject().put("reason", "Software IME did not become visible; no permission or keyboard setting was changed"))
                shot("editor-ime-unavailable", activity)
            } else {
                var previousImeBottom = -1
                var stableSince = android.os.SystemClock.uptimeMillis()
                await("IME toolbar layout and animation settled") {
                    val bottom = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    if (bottom != previousImeBottom) {
                        previousImeBottom = bottom
                        stableSince = android.os.SystemClock.uptimeMillis()
                    }
                    bottom > 0 && !activity.findViewById<View>(R.id.keyboard_container).isLayoutRequested &&
                        android.os.SystemClock.uptimeMillis() - stableSince >= 350
                }
                main {
                    val decor = activity.window.decorView
                    val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                    val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val decorLocation = IntArray(2).also { decor.getLocationOnScreen(it) }
                    val imeTop = decorLocation[1] + decor.height - imeBottom
                    val bar = bounds(activity.findViewById(R.id.keyboard_container))
                    contract("editor-ime-toolbar-unobscured", bar.bottom <= imeTop + 1,
                        JSONObject().put("toolbar", rectJson(bar)).put("imeTopPx", imeTop).put("imeBottomPx", imeBottom))
                }
                shot("editor-ime", activity)
            }
        } finally { close(activity) }
    }

    private fun captureScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            record("scanner", "UNVERIFIED", JSONObject().put("reason", "Camera permission was not already granted; scanner was not launched and permission was not changed"))
            return
        }
        val activity = launch(ScannerActivity::class.java)
        try {
            await("scanner toolbar") { activity.findViewById<Toolbar>(R.id.toolbar)?.menu?.size() == 1 }
            val streaming = await("camera preview streaming", timeoutMs = 6000, required = false) {
                activity.binding.previewView.previewStreamState.value == androidx.camera.view.PreviewView.StreamState.STREAMING
            }
            record("scanner-preview", if (streaming) "PASS" else "UNVERIFIED", JSONObject().put(
                "scope", "CameraX preview stream state only; QR decoding and the scene still need separate verification"))
            main {
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val controls = descendants(toolbar).filter { it.isClickable && it.isShown && it.width > 0 && it !is ViewGroup }
                contract("scanner-toolbar-actions-present", controls.size >= 2, JSONObject().put("count", controls.size))
                controls.forEachIndexed { index, view -> target("scanner-toolbar-$index", view) }
                val flash = activity.binding.ivFlashlight
                target("scanner-flash", flash)
                nonOverlap("scanner-control-overlap", controls + flash)
                safeSides("scanner-cutout-safety", activity, controls + flash)
            }
            shot("scanner", activity)
        } finally { close(activity) }
    }

    private fun target(name: String, view: View, requireFullyVisible: Boolean = true) {
        val density = view.resources.displayMetrics.density
        val min = 48f * density - 1f
        val visible = Rect()
        val shown = view.getGlobalVisibleRect(visible)
        val label = view.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: (view as? TextView)?.text?.toString().orEmpty()
        val detail = JSONObject().put("widthDp", view.width / density.toDouble()).put("heightDp", view.height / density.toDouble())
            .put("label", label).put("bounds", rectJson(bounds(view))).put("visibleBounds", rectJson(visible))
        contract(name, view.width >= min && view.height >= min && label.isNotBlank() && shown &&
            (!requireFullyVisible || (visible.width() >= view.width - 1 && visible.height() >= view.height - 1)), detail)
    }
    private fun nonOverlap(name: String, views: List<View>) {
        val visible = views.map { view -> Rect().also { view.getGlobalVisibleRect(it) } }
        val overlaps = JSONArray()
        for (a in visible.indices) for (b in a + 1 until visible.size) {
            if (Rect.intersects(visible[a], visible[b])) overlaps.put(JSONArray(listOf(a, b)))
        }
        contract(name, overlaps.length() == 0, JSONObject().put("overlaps", overlaps))
    }
    private fun safeSides(name: String, activity: Activity, views: List<View>) {
        val decor = activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val origin = IntArray(2).also { decor.getLocationOnScreen(it) }
        val left = origin[0] + (insets?.left ?: 0)
        val right = origin[0] + decor.width - (insets?.right ?: 0)
        contract(name, views.all { bounds(it).let { rect -> rect.left >= left && rect.right <= right } },
            JSONObject().put("safeLeft", left).put("safeRight", right))
    }
    private fun bounds(view: View): Rect {
        val point = IntArray(2).also { view.getLocationOnScreen(it) }
        return Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }
    private fun rectJson(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun contract(name: String, pass: Boolean, details: JSONObject = JSONObject()) {
        record(name, if (pass) "PASS" else "FAIL", details)
        if (!pass) failures.add(name)
    }
    private fun record(name: String, status: String, details: JSONObject) { checks.put(details.put("name", name).put("status", status)) }
    private fun surface(name: String, action: () -> Unit) {
        try { action() } catch (error: Throwable) {
            failures.add("$name: ${error.javaClass.simpleName}: ${error.message}")
            record(name, "FAIL", JSONObject().put("errorType", error.javaClass.simpleName))
        } finally { writeIndex("RUNNING") }
    }
    private fun shot(name: String, activity: Activity) {
        await("$name visible") { activity.window.decorView.hasWindowFocus() && activity.window.decorView.isLaidOut }
        val frames = CountDownLatch(1)
        main { activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { frames.countDown() } } }
        check(frames.await(3, TimeUnit.SECONDS)) { "No rendered frame for $name" }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(output, "$name.png")
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        screenshots.put(main {
            val configuration = activity.resources.configuration
            JSONObject().put("name", name).put("file", file.name).put("activity", activity.javaClass.simpleName)
                .put("fontScale", configuration.fontScale.toDouble()).put("orientation", configuration.orientation)
                .put("night", configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                .put("density", activity.resources.displayMetrics.density.toDouble())
        })
        writeIndex("RUNNING")
    }
    private fun <T : Activity> launch(type: Class<T>, extras: Bundle? = null): T {
        launchTarget.set(type); launchResult.set(null); launchReady = CountDownLatch(1)
        main { context.startActivity(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { extras?.let { putExtras(it) } }) }
        check(launchReady.await(10, TimeUnit.SECONDS)) { "Activity did not resume: ${type.simpleName}" }
        val result = checkNotNull(type.cast(checkNotNull(launchResult.get())))
        launchTarget.set(null)
        await("${type.simpleName} layout") { result.window.decorView.isLaidOut && result.window.decorView.hasWindowFocus() }
        return result
    }
    private fun close(activity: Activity) {
        if (destroyed[activity]?.count == 0L) return
        main {
            if (!activity.isDestroyed) {
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                activity.finish()
            }
        }
        check(destroyed[activity]?.await(8, TimeUnit.SECONDS) != false) { "Activity teardown timed out: ${activity.javaClass.simpleName}" }
    }
    private fun await(name: String, timeoutMs: Long = 10000, required: Boolean = true, condition: () -> Boolean): Boolean {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var poll: Runnable
        poll = Runnable {
            try { if (condition()) done.countDown() else handler.postDelayed(poll, 25) }
            catch (error: Throwable) { failure.set(error); done.countDown() }
        }
        handler.post(poll)
        val success = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        handler.removeCallbacks(poll)
        failure.get()?.let { throw it }
        if (required) check(success) { "Timed out: $name" }
        return success
    }
    private fun <T> main(action: () -> T): T {
        val done = CountDownLatch(1)
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        handler.post { try { result.set(action()) } catch (error: Throwable) { failure.set(error) } finally { done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS)) { "Main thread action timed out" }
        failure.get()?.let { throw it }
        return result.get()
    }
    private fun snapshot(dao: KeyValuePair.Dao) = dao.all().map { row ->
        KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
    }
    private fun restore(dao: KeyValuePair.Dao, rows: List<KeyValuePair>) {
        val keys = rows.map { it.key }.toSet()
        dao.all().filter { it.key !in keys }.forEach { dao.delete(it.key) }
        rows.forEach { dao.put(it) }
        val restored = dao.all().associateBy { it.key }
        check(restored.keys == keys && rows.all { expected ->
            restored[expected.key]?.let { it.valueType == expected.valueType && it.value.contentEquals(expected.value) } == true
        }) { "Settings snapshot restore mismatch" }
    }
    private fun writeIndex(status: String) {
        File(output, "index.json").writeText(JSONObject().put("status", status).put("label", label)
            .put("checks", checks).put("screenshots", screenshots).put("failures", JSONArray(failures))
            .put("roomRestored", !roomIsolated)
            .put("fixturePolicy", "Only fixed local example records and default settings are rendered; original Room rows/BLOBs and sequence counters are restored from internal backup tables.")
            .put("note", "Screenshots require human review. Font size and orientation are externally controlled; actual values are recorded. Main settings theme-recreation behavior is covered by MainUiRecoveryNativeTest.")
            .toString(2))
    }
}
