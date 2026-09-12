package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.chip.Chip
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.*
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.QRCodeDialog
import moe.matsuri.nb4a.TempDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in visual evidence only. Never press connect, update, save, delete or grant VPN consent. */
@RunWith(AndroidJUnit4::class)
class VisualSurfaceCaptureTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var output: File
    private val captures = JSONArray()
    private var activeSurface = "setup"
    private val groups = mutableListOf<Long>()
    private val profiles = mutableListOf<Long>()
    private val rules = mutableListOf<Long>()
    private var groupId = 0L
    private var emptyGroupId = 0L
    private var profileId = 0L
    private var chainId = 0L
    private var subscriptionGroupId = 0L
    private var ruleId = 0L

    @Test
    fun captureNativeSurfaceFamilies() {
        // This check precedes all DB reads, fixture writes, activities and screenshots.
        assumeTrue("Requires -e vialenVisualCapture true", InstrumentationRegistry.getArguments()
            .getString("vialenVisualCapture") == "true")
        val arguments = InstrumentationRegistry.getArguments()
        val requestedMode = arguments.getString("vialenVisualMode") ?: "all"
        val requestedSection = arguments.getString("vialenVisualSection") ?: "all"
        require(requestedMode in setOf("light", "dark", "all")) { "Invalid vialenVisualMode=$requestedMode" }
        require(requestedSection in setOf("main", "forms", "standalone", "conditions", "compact", "typography", "all")) { "Invalid vialenVisualSection=$requestedSection" }
        checkNoStartedService()
        check(activities().isEmpty()) { "Close app activities before this isolated capture run" }
        output = File(checkNotNull(context.getExternalFilesDir(null)), "ui-v1.6/${System.currentTimeMillis()}")
        check(output.mkdirs())
        val publicBefore = snapshot(PublicDatabase.kvPairDao)
        val cacheBefore = snapshot(TempDatabase.profileCacheDao)
        val themeBefore = Theme.currentNightMode
        val delegateBefore = AppCompatDelegate.getDefaultNightMode()
        val stateBefore = DataStore.serviceState
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        try {
            // Suppress automatic connection preference; no service controls are invoked.
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            // Covered by publicBefore's exact type/byte snapshot and finally restoration.
            DataStore.configurationStore.putBoolean("managedRuntimeNoticeAcknowledged", true)
            createFixtures()
            for ((mode, label) in listOf(2 to "light", 1 to "dark").filter { requestedMode == "all" || it.second == requestedMode }) {
                DataStore.nightTheme = mode
                Theme.currentNightMode = mode
                instrumentation.runOnMainSync { Theme.applyNightTheme() }
                DataStore.selectedGroup = groupId
                DataStore.selectedProxy = profileId
                if (requestedSection == "all" || requestedSection == "main") {
                    activeSurface = "$label/main"; captureMain(label)
                }
                if (requestedSection == "all" || requestedSection == "forms") {
                    activeSurface = "$label/forms"; captureForms(label)
                }
                if (requestedSection == "all" || requestedSection == "standalone") {
                    activeSurface = "$label/standalone"; captureStandalone(label)
                }
                if (requestedSection == "conditions") {
                    activeSurface = "$label/conditions"; captureConditions(label)
                }
                if (requestedSection == "compact") {
                    activeSurface = "$label/compact"; captureCompact(label)
                }
                if (requestedSection == "typography") {
                    activeSurface = "$label/typography"; captureTypography(label)
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Each step runs even after another cleanup failure; original failure is retained.
            attempt { closeOwnedActivities() }
            attempt { profiles.forEach { SagerDatabase.proxyDao.deleteById(it) } }
            attempt { rules.forEach { SagerDatabase.rulesDao.deleteById(it) } }
            attempt { groups.forEach { SagerDatabase.groupDao.deleteById(it) } }
            attempt { restore(PublicDatabase.kvPairDao, publicBefore) }
            attempt { restore(TempDatabase.profileCacheDao, cacheBefore) }
            attempt {
                Theme.currentNightMode = themeBefore
                DataStore.serviceState = stateBefore
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(delegateBefore) }
            }
            attempt { checkNoStartedService() }
            attempt {
                check(groups.all { SagerDatabase.groupDao.getById(it) == null })
                check(profiles.all { SagerDatabase.proxyDao.getById(it) == null })
                check(rules.all { SagerDatabase.rulesDao.getById(it) == null })
            }
            val report = JSONObject().put("status", if (failure == null) "CAPTURED" else "FAILED")
                .put("requestedMode", requestedMode).put("requestedSection", requestedSection)
                .put("lastSurface", activeSurface)
                .put("screenshots", captures).put("failure", failure?.toString() ?: JSONObject.NULL)
                .put("meaning", "Actual rendered screenshots; no visual quality, protocol or release PASS implied")
                .put("excluded", JSONArray(listOf("VPN/permission handoff and transient shortcuts", "camera grant/decoding",
                    "remote dashboard/remote subscriptions", "all transport conditional permutations", "real connection states")))
            File(output, "capture-index.json").writeText(report.toString(2))
            println("VIALEN_VISUAL_CAPTURE ${output.absolutePath} count=${captures.length()} status=${report.getString("status")}")
        }
        failure?.let { throw it }
    }

    private fun createFixtures() {
        fun group(name: String): Long = SagerDatabase.groupDao.createGroup(ProxyGroup(
            name = name, userOrder = -1000L + groups.size)).also { groups.add(it) }
        groupId = group("QA Visual · 本地节点")
        emptyGroupId = group("QA Visual · Empty")
        val sub = SubscriptionBean().also { it.initializeDefaultValues(); it.link = "https://visual.invalid/never-fetch"; it.autoUpdate = false }
        subscriptionGroupId = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "QA Visual · Subscription", userOrder = -998,
            type = GroupType.SUBSCRIPTION, subscription = sub)).also { groups.add(it) }
        fun profile(bean: AbstractBean, status: Int = 0, ping: Int = 0): Long {
            bean.initializeDefaultValues()
            val entity = ProxyEntity(groupId = groupId, userOrder = profiles.size.toLong(), status = status, ping = ping)
            entity.putBean(bean)
            return SagerDatabase.proxyDao.addProxy(entity).also { profiles.add(it) }
        }
        profileId = profile(SOCKSBean().also { it.name = "QA Blue · localhost"; it.serverAddress = "127.0.0.1"; it.serverPort = 9 }, 1, 42)
        profile(SOCKSBean().also { it.name = "QA Unavailable · 长名称视觉截断测试"; it.serverAddress = "127.0.0.1"; it.serverPort = 9 }, 2)
        chainId = profile(ChainBean().also { it.name = "QA Chain"; it.proxies = listOf(profileId) })
        ruleId = SagerDatabase.rulesDao.createRule(RuleEntity(name = "QA Rule · Local", userOrder = -1000,
            enabled = true, domains = "full:visual.invalid", network = "tcp", outbound = profileId,
            packages = setOf(context.packageName))).also { rules.add(it) }
        DataStore.selectedGroup = groupId
        DataStore.selectedProxy = profileId
    }

    private fun captureMain(mode: String) {
        withActivity(MainActivity::class.java) { activity ->
            await { descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.id == R.id.profile_name && it.text.toString().startsWith("QA Blue") } }
            shot("$mode/configuration-populated", activity)
            onMain { check(activity.findViewById<View>(R.id.action_misc).performClick()) }
            shot("$mode/configuration-toolbar-menu", activity)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            onMain { check(descendants(activity.window.decorView).first { it.id == R.id.share && it.isShown }.performClick()) }
            shot("$mode/profile-share-popup", activity)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            onMain { descendants(activity.window.decorView).filterIsInstance<DrawerLayout>().first().openDrawer(GravityCompat.START) }
            shot("$mode/drawer", activity)
            onMain { descendants(activity.window.decorView).filterIsInstance<DrawerLayout>().first().closeDrawers() }
            onMain { QRCodeDialog("socks://127.0.0.1:9", "QA Visual QR").show(activity.supportFragmentManager, "qa-qr") }
            shot("$mode/qr-dialog", activity)
            onMain { (activity.supportFragmentManager.findFragmentByTag("qa-qr") as QRCodeDialog).dismiss() }
            onMain { activity.snackbar("QA offline feedback · 可撤销提示").setAction(R.string.undo) {}.show() }
            shot("$mode/snackbar", activity)
            for ((id, name) in listOf(R.id.nav_group to "groups", R.id.nav_route to "routes",
                R.id.nav_settings to "settings", R.id.nav_about to "about")) {
                onMain { activity.displayFragmentWithId(id) }
                settle()
                shot("$mode/$name-top", activity)
                if (name == "groups") {
                    await { descendants(activity.window.decorView).any { it.id == R.id.options && it.isShown } }
                    onMain { check(descendants(activity.window.decorView).first { it.id == R.id.options && it.isShown }.performClick()) }
                    shot("$mode/group-popup", activity)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                }
                if (name == "settings") {
                    await { preferenceFragment(activity) != null }
                    expandPreferenceSections(activity)
                    scrollPages("$mode/settings", activity, 12)
                    for (key in listOf("remoteDns", "nightTheme", "mtu", "connectionTestURL")) {
                        clickPreference(activity, key)
                        shot("$mode/settings-dialog-$key", activity)
                        dismissFloating(activity)
                    }
                    val diagnosticsWereEnabled = libcore.Libcore.diagnosticRemainingMillis() > 0
                    clickPreference(activity, "uiDetailedDiagnostics")
                    shot("$mode/settings-diagnostics-confirmation", activity)
                    // Back cancels; never press the enable/disable action.
                    dismissFloating(activity)
                    if (!diagnosticsWereEnabled) check(libcore.Libcore.diagnosticRemainingMillis() == 0L) {
                        "Cancelling diagnostics confirmation enabled diagnostics"
                    }
                }
            }
        }
        DataStore.selectedGroup = emptyGroupId
        withActivity(MainActivity::class.java) { shot("$mode/configuration-empty-group", it) }
        DataStore.selectedGroup = groupId
    }

    /** Targeted text-role regression, independent of installed-app enumeration permissions. */
    private fun captureTypography(mode: String) {
        withActivity(MainActivity::class.java) { activity ->
            await("typography fixture profile bound") {
                descendants(activity.window.decorView).filterIsInstance<TextView>()
                    .any { it.id == R.id.profile_name && it.text.toString().startsWith("QA Blue") }
            }
            shot("$mode/typography-nodes", activity)
            onMain { check(activity.findViewById<View>(R.id.action_add).performClick()) }
            shot("$mode/typography-add-sheet", activity)
            val label = context.getString(R.string.ui_manual_config)
            await("manual configuration action visible") {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                    ?.any { it.text?.toString() == label } == true
            }
            var node = instrumentation.uiAutomation.rootInActiveWindow
                .findAccessibilityNodeInfosByText(label).first { it.text?.toString() == label }
            while (!node.isClickable) node = checkNotNull(node.parent)
            check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            shot("$mode/typography-protocol-picker", activity)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            for ((id, name) in listOf(R.id.nav_group to "groups", R.id.nav_route to "routes",
                R.id.nav_settings to "settings", R.id.nav_about to "about")) {
                onMain { activity.displayFragmentWithId(id) }
                shot("$mode/typography-$name", activity)
            }
        }
        withActivity(AssetsActivity::class.java) { shot("$mode/typography-assets", it) }
        withActivity(GroupSettingsActivity::class.java, { putExtra("id", groupId) }) {
            clickPreference(it, "groupName")
            shot("$mode/typography-group-name", it)
            dismissFloating(it)
        }
        withActivity(RouteSettingsActivity::class.java, { putExtra("id", ruleId) }) {
            clickPreference(it, "routeName")
            shot("$mode/typography-route-name", it)
            dismissFloating(it)
        }
        withActivity(io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity::class.java, { putExtra("id", profileId) }) {
            clickPreference(it, "profileName")
            shot("$mode/typography-profile-name", it)
            dismissFloating(it)
            clickPreference(it, "serverAddress")
            shot("$mode/typography-technical-address", it)
            dismissFloating(it)
        }
        DataStore.selectedGroup = emptyGroupId
        withActivity(MainActivity::class.java) { shot("$mode/typography-empty-group", it) }
        DataStore.selectedGroup = groupId
    }

    private fun captureForms(mode: String) {
        val entries = listOf(
            "socks" to "io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity",
            "http" to "io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity",
            "shadowsocks" to "io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity",
            "vmess" to "io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity",
            "vless" to "io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity",
            "trojan" to "io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity",
            "hysteria" to "io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity",
            "tuic" to "io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity",
            "shadowtls" to "moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity",
            "anytls" to "moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity",
            "wireguard" to "io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity",
            "custom-config" to "moe.matsuri.nb4a.proxy.config.ConfigSettingActivity",
            "chain" to "io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity")
        for ((name, className) in entries) {
            @Suppress("UNCHECKED_CAST")
            val cls = Class.forName(className) as Class<Activity>
            withActivity(cls, { if (name == "vless") putExtra("vless", true) }) { activity ->
                await { preferenceFragment(activity) != null }
                shot("$mode/manual-$name-top", activity)
                scrollPages("$mode/manual-$name", activity, 12)
            }
        }
        withActivity(GroupSettingsActivity::class.java, { putExtra("id", groupId) }) {
            await { preferenceFragment(it) != null }; shot("$mode/group-basic", it)
            clickPreference(it, "groupName"); shot("$mode/group-name-dialog", it)
        }
        withActivity(GroupSettingsActivity::class.java, { putExtra("id", subscriptionGroupId) }) {
            await { preferenceFragment(it) != null }; shot("$mode/group-subscription", it)
            scrollPages("$mode/group-subscription", it, 4)
        }
        withActivity(RouteSettingsActivity::class.java, { putExtra("id", ruleId) }) {
            await { preferenceFragment(it) != null }; shot("$mode/route-edit", it)
            scrollPages("$mode/route-edit", it, 4)
            clickPreference(it, "routeDomain"); shot("$mode/route-domain-dialog", it)
        }
        withActivity(io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity::class.java, { putExtra("id", chainId) }) {
            await { preferenceFragment(it) != null }; shot("$mode/chain-populated", it)
        }
    }

    private fun captureStandalone(mode: String) {
        for ((cls, name) in listOf(AssetsActivity::class.java to "assets", AppListActivity::class.java to "rule-app-list",
            AppManagerActivity::class.java to "global-app-list", ProfileSelectActivity::class.java to "profile-picker",
            SwitchActivity::class.java to "switch-dialog-declared")) {
            withActivity(cls) { activity ->
                activeSurface = "$mode/$name"
                if (name in setOf("rule-app-list", "global-app-list")) {
                    // A filtered empty result is legitimate on a fresh device. First require the
                    // production loading animation to end, then enable the real system-app chip.
                    await("$mode/$name loading finished") {
                        !activity.findViewById<View>(R.id.loading).isShown &&
                            (activity.findViewById<View>(R.id.list).isShown ||
                                activity.findViewById<View>(R.id.app_placeholder).isShown)
                    }
                    shot("$mode/$name-default-filter", activity)
                    onMain {
                        val chip = activity.findViewById<Chip>(R.id.show_system_apps)
                        check(chip.isCheckable && chip.isEnabled && chip.isShown) {
                            "$mode/$name system-app chip unavailable: checkable=${chip.isCheckable}, enabled=${chip.isEnabled}, shown=${chip.isShown}"
                        }
                        // CompoundButton may toggle while performClick returns false when no
                        // OnClickListener exists; checked state and bound rows are the oracle.
                        if (!chip.isChecked) chip.performClick()
                        check(chip.isChecked) { "$mode/$name system-app chip did not enable" }
                    }
                    // Do not weaken the populated-state oracle: system apps must now bind visibly.
                    await("$mode/$name populated with system apps enabled") {
                        val list = activity.findViewById<RecyclerView>(R.id.list)
                        list.isShown && (list.adapter?.itemCount ?: 0) > 0 && list.childCount > 0 &&
                            !activity.findViewById<View>(R.id.loading).isShown
                    }
                } else if (name in setOf("profile-picker", "switch-dialog-declared")) {
                    await("$mode/$name fixture profiles bound") { descendants(activity.window.decorView).filterIsInstance<RecyclerView>()
                        .any { it.isShown && (it.adapter?.itemCount ?: 0) > 0 } }
                }
                settle(); shot("$mode/$name", activity)
                scrollPages("$mode/$name", activity, 2)
            }
        }
        DataStore.selectedGroup = emptyGroupId
        withActivity(ProfileSelectActivity::class.java) { shot("$mode/profile-picker-empty", it) }
        DataStore.selectedGroup = groupId
        DataStore.profileCacheStore.putString("qaVisualJson", "{\n  \"name\": \"Vialen Visual\",\n  \"enabled\": true,\n  \"port\": 1080,\n  \"values\": [null, 1, 2]\n}")
        withActivity(ConfigEditActivity::class.java, { putExtra("key", "qaVisualJson") }) { activity ->
            shot("$mode/editor-json", activity)
            onMain { activity.binding.editor.requestFocus(); activity.binding.editor.performClick() }
            // An explicit IME request inspects editor viewport and extended keyboard without editing data.
            onMain { (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(activity.binding.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            await("$mode/editor-ime root WindowInsets reports IME visible") {
                ViewCompat.getRootWindowInsets(activity.binding.root)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            shot("$mode/editor-ime", activity)
            onMain { activity.dirty = true; activity.onBackPressed() }
            shot("$mode/editor-unsaved-dialog", activity)
        }
        // Mark scanning consumed at CREATED, before asynchronous camera results can import anything.
        // Permission is never granted/revoked here; the shell or permission boundary is observed.
        val scannerGuard = ActivityLifecycleCallback { activity, stage ->
            if (activity is ScannerActivity && stage == Stage.CREATED) activity.finished.set(true)
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(scannerGuard)
        try {
            withActivity(ScannerActivity::class.java) { shot("$mode/scanner-observed-permission-state", it) }
        } finally { monitor.removeLifecycleCallback(scannerGuard) }
    }

    /** Explicit additional matrix; deliberately excluded from section=all. */
    private fun captureConditions(mode: String) {
        data class Variant(val name: String, val className: String, val values: Map<String, String>,
            val visible: Map<String, Boolean>, val vless: Boolean = false)
        val prefix = "io.nekohasekai.sagernet.ui.profile."
        val variants = listOf(
            Variant("socks4", "SocksSettingsActivity", mapOf("serverProtocol" to "0"), mapOf("serverPassword" to false)),
            Variant("socks4a", "SocksSettingsActivity", mapOf("serverProtocol" to "1"), mapOf("serverPassword" to false)),
            Variant("socks5-auth", "SocksSettingsActivity", mapOf("serverProtocol" to "2", "serverUsername" to "qa", "serverPassword" to "visual-only"), mapOf("serverPassword" to true)),
            Variant("vmess-tcp-none", "VMessSettingsActivity", mapOf("type" to "tcp", "security" to "none"), mapOf("host" to false, "path" to false, "serverSecurityCategory" to false)),
            Variant("vmess-ws-tls", "VMessSettingsActivity", mapOf("type" to "ws", "security" to "tls"), mapOf("host" to true, "path" to true, "serverWsCategory" to true, "serverSecurityCategory" to true)),
            Variant("vmess-http-tls", "VMessSettingsActivity", mapOf("type" to "http", "security" to "tls"), mapOf("host" to true, "path" to true, "serverWsCategory" to false)),
            Variant("vmess-grpc-tls", "VMessSettingsActivity", mapOf("type" to "grpc", "security" to "tls"), mapOf("host" to false, "path" to true, "serverWsCategory" to false)),
            // Reality is a TLS field pair in this UI, not a separate security dropdown value.
            Variant("vless-tcp-tls-reality-fields", "VMessSettingsActivity", mapOf("type" to "tcp", "security" to "tls", "realityPubKey" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "realityShortId" to "0123456789abcdef"), mapOf("serverTlsCamouflageCategory" to true, "serverSecurityCategory" to true), true),
            Variant("vless-ws-tls", "VMessSettingsActivity", mapOf("type" to "ws", "security" to "tls"), mapOf("serverWsCategory" to true, "serverSecurityCategory" to true), true),
            Variant("vless-grpc-none", "VMessSettingsActivity", mapOf("type" to "grpc", "security" to "none"), mapOf("host" to false, "path" to true, "serverSecurityCategory" to false), true),
            Variant("hysteria1-auth-none", "HysteriaSettingsActivity", mapOf("protocolVersion" to "1", "serverAuthType" to "0"), mapOf("serverPassword" to false, "serverAuthType" to true, "hysteria2AdvancedCategory" to false)),
            Variant("hysteria1-auth-string", "HysteriaSettingsActivity", mapOf("protocolVersion" to "1", "serverAuthType" to "1", "serverPassword" to "visual-only"), mapOf("serverPassword" to true, "serverALPN" to true, "hysteria2AdvancedCategory" to false)),
            Variant("hysteria2-gecko", "HysteriaSettingsActivity", mapOf("protocolVersion" to "2", "serverObfsType" to "gecko"), mapOf("serverAuthType" to false, "serverALPN" to false, "hysteria2AdvancedCategory" to true, "serverObfsMinPacketSize" to true, "serverObfsMaxPacketSize" to true)),
            Variant("http-none", "HttpSettingsActivity", mapOf("security" to "none"), mapOf("username" to true, "password" to true, "type" to false, "serverSecurityCategory" to false)),
            Variant("http-tls", "HttpSettingsActivity", mapOf("security" to "tls"), mapOf("username" to true, "password" to true, "serverSecurityCategory" to true))
        )
        for (variant in variants) {
            @Suppress("UNCHECKED_CAST")
            val cls = Class.forName(prefix + variant.className) as Class<Activity>
            withActivity(cls, { if (variant.vless) putExtra("vless", true) }) { activity ->
                activeSurface = "$mode/conditions-${variant.name}"
                await("${variant.name} preference initialization") { preferenceFragment(activity) != null }
                variant.values.forEach { (key, value) -> setEditorPreference(activity, key, value) }
                onMain {
                    val fragment = preferenceFragment(activity)!!
                    variant.visible.forEach { (key, expected) ->
                        val preference = checkNotNull(fragment.findPreference<androidx.preference.Preference>(key)) { "$activeSurface missing $key" }
                        check(preference.isVisible == expected) { "$activeSurface $key visible=${preference.isVisible}, expected=$expected" }
                    }
                }
                shot("$mode/conditions-${variant.name}-top", activity)
                scrollPages("$mode/conditions-${variant.name}", activity, 12)
            }
        }
    }

    /** Drive production preference change callbacks, then persist only the editor cache. */
    private fun setEditorPreference(activity: Activity, key: String, value: String) {
        onMain {
            val preference = checkNotNull(preferenceFragment(activity)?.findPreference<androidx.preference.Preference>(key)) {
                "$activeSurface missing editor preference $key"
            }
            check(preference.preferenceDataStore === DataStore.profileCacheStore) { "$activeSurface $key is not an editor-cache preference" }
            when (preference) {
                is androidx.preference.ListPreference -> {
                    check(preference.entryValues.any { it.toString() == value }) { "$activeSurface unsupported $key=$value" }
                    check(preference.callChangeListener(value)) { "$activeSurface rejected $key=$value" }
                    preference.value = value
                    check(preference.value == value)
                }
                is androidx.preference.EditTextPreference -> {
                    check(preference.callChangeListener(value)) { "$activeSurface rejected editor value for $key" }
                    preference.text = value
                    check(preference.text == value)
                }
                else -> error("$activeSurface unsupported preference kind for $key: ${preference.javaClass.name}")
            }
        }
        settle()
    }

    /** Small representative suite for externally prepared width/font-scale configurations. */
    private fun captureCompact(mode: String) {
        withActivity(MainActivity::class.java) { activity ->
            await("compact fixture profile") { descendants(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.id == R.id.profile_name && it.text.toString().startsWith("QA Blue") } }
            shot("$mode/compact-main", activity)
            onMain { activity.displayFragmentWithId(R.id.nav_settings) }
            await("compact settings") { preferenceFragment(activity) != null }
            shot("$mode/compact-settings-top", activity)
            captureListBottom("$mode/compact-settings-bottom", activity)
            clickPreference(activity, "nightTheme")
            shot("$mode/compact-single-choice-dialog", activity)
            dismissFloating(activity)
        }
        withActivity(io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity::class.java) { activity ->
            await("compact VMess preferences") { preferenceFragment(activity) != null }
            setEditorPreference(activity, "type", "ws")
            setEditorPreference(activity, "security", "tls")
            shot("$mode/compact-vmess-ws-tls-top", activity)
            captureListBottom("$mode/compact-vmess-ws-tls-bottom", activity)
        }
        withActivity(AppListActivity::class.java) { activity ->
            activeSurface = "$mode/compact-app-list"
            await("compact app loading finished") { !activity.findViewById<View>(R.id.loading).isShown }
            onMain {
                val chip = activity.findViewById<Chip>(R.id.show_system_apps)
                check(chip.isCheckable && chip.isEnabled && chip.isShown)
                if (!chip.isChecked) chip.performClick()
                check(chip.isChecked)
            }
            await("compact system-app rows bound") {
                val list = activity.findViewById<RecyclerView>(R.id.list)
                list.isShown && (list.adapter?.itemCount ?: 0) > 0 && list.childCount > 0
            }
            shot("$mode/compact-app-list", activity)
        }
        DataStore.profileCacheStore.putString("qaVisualCompactJson", "{\n  \"name\": \"Vialen compact\",\n  \"enabled\": true\n}")
        withActivity(ConfigEditActivity::class.java, { putExtra("key", "qaVisualCompactJson") }) { activity ->
            onMain {
                activity.binding.editor.requestFocus()
                (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(activity.binding.editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            await("$mode/compact-editor-ime root WindowInsets reports IME visible") {
                ViewCompat.getRootWindowInsets(activity.binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            shot("$mode/compact-editor-ime", activity)
        }
    }

    private fun captureListBottom(name: String, activity: Activity) {
        lateinit var list: RecyclerView
        var last = -1
        onMain {
            list = checkNotNull(preferenceFragment(activity)).listView
            last = (list.adapter?.itemCount ?: 0) - 1
            check(last >= 0) { "$name has no preference rows" }
            list.scrollToPosition(last)
        }
        await("$name final preference row laid out") { list.findViewHolderForAdapterPosition(last) != null }
        shot(name, activity)
    }

    private fun <A : Activity> withActivity(cls: Class<A>, extras: Intent.() -> Unit = {}, body: (A) -> Unit) {
        activeSurface = "$activeSurface -> ${cls.simpleName}"
        // Normal foreground launcher entry avoids ROM background-activity restrictions.
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/io.nekohasekai.sagernet.ui.MainActivity"
        ).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val scenario = ActivityScenario.launch<A>(Intent(context, cls).apply(extras))
        lateinit var activity: A
        scenario.onActivity { activity = it }
        try {
            settle()
            body(activity)
        } finally {
            onMain { activity.finish() }
            await("capture activity destroyed") { activity.isDestroyed }
            scenario.close()
        }
    }

    private fun expandPreferenceSections(activity: Activity) {
        repeat(12) {
            var position = -1
            lateinit var list: RecyclerView
            onMain {
                list = preferenceFragment(activity)!!.listView
                val adapter = list.adapter as PreferenceGroupAdapter
                position = (0 until adapter.itemCount).firstOrNull {
                    adapter.getItem(it)?.javaClass?.simpleName == "ExpandButton"
                } ?: -1
                if (position >= 0) list.scrollToPosition(position)
            }
            if (position < 0) return
            await { list.findViewHolderForAdapterPosition(position) != null }
            onMain { check(list.findViewHolderForAdapterPosition(position)!!.itemView.performClick()) }
            settle()
        }
        error("Too many collapsed preference sections")
    }

    private fun clickPreference(activity: Activity, key: String, longClick: Boolean = false) {
        await { preferenceFragment(activity)?.findPreference<androidx.preference.Preference>(key) != null }
        var position = -1
        lateinit var list: RecyclerView
        onMain {
            val fragment = preferenceFragment(activity)!!
            val preference = fragment.findPreference<androidx.preference.Preference>(key)!!
            list = fragment.listView
            position = (list.adapter as PreferenceGroupAdapter).getPreferenceAdapterPosition(preference)
            check(position >= 0) { "Preference not visible: $key" }
            list.scrollToPosition(position)
        }
        await { list.findViewHolderForAdapterPosition(position) != null }
        onMain {
            val item = list.findViewHolderForAdapterPosition(position)!!.itemView
            check(if (longClick) item.performLongClick() else item.performClick()) { "No preference click: $key" }
        }
        settle()
    }

    private fun dismissFloating(activity: Activity) {
        // Back dismisses a Spinner popup (or its IME); explicitly dismiss surviving preference dialogs.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        onMain {
            fun dismiss(fragments: List<Fragment>) {
                fragments.forEach {
                    if (it is DialogFragment) it.dismissAllowingStateLoss()
                    if (it.isAdded) dismiss(it.childFragmentManager.fragments)
                }
            }
            (activity as? FragmentActivity)?.let { dismiss(it.supportFragmentManager.fragments) }
            descendants(activity.window.decorView).filterIsInstance<Toolbar>().forEach { it.dismissPopupMenus() }
            (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        settle()
    }

    private fun scrollPages(name: String, activity: Activity, maximum: Int) {
        repeat(maximum) { page ->
            var moved = false
            onMain {
                val list = preferenceFragment(activity)?.listView ?: descendants(activity.window.decorView)
                    .filterIsInstance<RecyclerView>().firstOrNull { it.isShown && it.canScrollVertically(1) }
                if (list != null && list.canScrollVertically(1)) {
                    list.scrollBy(0, (list.height * 0.8).toInt()); moved = true
                }
            }
            if (!moved) return
            shot("$name-scroll-${page + 1}", activity)
        }
    }

    private fun shot(name: String, activity: Activity) {
        activeSurface = name
        settle()
        check(!activity.isFinishing && !activity.isDestroyed) { "Capture target closed: $name" }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable: $name" }
        val file = File(output, name.replace('/', '-') + ".png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        captures.put(JSONObject().put("name", name).put("file", file.name).put("activity", activity.javaClass.name)
            .put("width", bitmap.width).put("height", bitmap.height).put("api", android.os.Build.VERSION.SDK_INT)
            .put("fontScale", activity.resources.configuration.fontScale.toDouble())
            .put("density", activity.resources.displayMetrics.density.toDouble())
            .put("note", "Rendered observation, requires human visual review"))
        bitmap.recycle()
    }

    private fun preferenceFragment(activity: Activity): PreferenceFragmentCompat? {
        fun walk(items: List<Fragment>): PreferenceFragmentCompat? {
            for (fragment in items) {
                if (fragment is PreferenceFragmentCompat && fragment.view != null && fragment.preferenceScreen != null) return fragment
                if (fragment.isAdded) walk(fragment.childFragmentManager.fragments)?.let { return it }
            }
            return null
        }
        return (activity as? FragmentActivity)?.let { walk(it.supportFragmentManager.fragments) }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    // IME/background animation callbacks can keep the queue non-idle after an Activity
    // closes on a physical ROM. Cross a main-thread boundary, then allow a render interval.
    private fun settle() { onMain { }; Thread.sleep(500); onMain { } }
    private fun await(expectation: String = "actual layout/preference binding", condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        do {
            var ready = false
            onMain { ready = condition() }
            if (ready) { settle(); return }
            Thread.sleep(100)
        } while (System.nanoTime() < until)
        error("Timed out at $activeSurface waiting for $expectation")
    }
    private fun activities(): List<Activity> {
        var result = emptyList<Activity>()
        onMain { result = Stage.values().filter { it != Stage.DESTROYED }.flatMap {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)
        }.filter { it.packageName == context.packageName }.distinct() }
        return result
    }
    private fun closeOwnedActivities() {
        val owned = activities()
        onMain { owned.forEach { it.finish() } }
        await { owned.all { it.isDestroyed } }
    }
    private fun snapshot(dao: KeyValuePair.Dao) = dao.all().map { row ->
        KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
    }
    private fun restore(dao: KeyValuePair.Dao, before: List<KeyValuePair>) {
        // Only delete new keys; restore exact types/bytes, including absent-versus-default distinction.
        val keys = before.map { it.key }.toSet()
        dao.all().filter { it.key !in keys }.forEach { dao.delete(it.key) }
        before.forEach { dao.put(it) }
        val actual = dao.all().associateBy { it.key }
        check(actual.keys == keys)
        before.forEach { expected -> check(actual[expected.key]?.let {
            it.valueType == expected.valueType && it.value.contentEquals(expected.value)
        } == true) { "DataStore restore mismatch: ${expected.key}" } }
    }
    private fun checkNoStartedService() {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "dumpsys activity services ${context.packageName}")).bufferedReader().use { it.readText() }
        check(dump.contains("ACTIVITY MANAGER SERVICES")) { "Cannot verify service state" }
        check(!dump.contains("startRequested=true") && !dump.contains("fgRequired=true") && !dump.contains("isForeground=true")) {
            "Capture requires stopped service; harness does not stop an existing session"
        }
    }
}
