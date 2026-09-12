package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.nekohasekai.sagernet.database.SagerDatabase
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.widget.VialenNavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: VialenNavigationView
    private var renderedState = BaseService.State.Idle
    private var pendingPage: Int? = null
    private var hasProfiles = false
    private var availabilityVersion = 0L
    private var activityStarted = false

    fun refreshProfileAvailability() {
        val version = ++availabilityVersion
        val excluded = (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.pendingRemovalIds().orEmpty()
        lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) {
                if (excluded.isEmpty()) SagerDatabase.proxyDao.hasProfiles()
                else SagerDatabase.proxyDao.hasProfilesExcluding(excluded)
            }
            if (version != availabilityVersion) return@launch
            hasProfiles = available
            syncPageControls(supportFragmentManager.findFragmentById(R.id.fragment_holder))
        }
    }

    private fun updateConnectionSummary(showControls: Boolean) {
        binding.connectionSummary.visibility = if (showControls && !renderedState.connected) View.VISIBLE else View.GONE
        binding.connectionSummary.setText(when (renderedState) {
            BaseService.State.Connecting -> R.string.connecting
            BaseService.State.Stopping -> R.string.stopping
            else -> R.string.not_connected
        })
    }

    private val pageCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            if (fragment.id == R.id.fragment_holder) syncPageControls(fragment)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        navigation = binding.navView
        navigation.onItemSelected = { id -> onNavigationItemSelected(id) }
        navigation.onCloseRequested = { binding.drawerLayout.closeDrawers() }
        binding.drawerLayout.setDrawerTitle(GravityCompat.START, getString(R.string.navigation_drawer_title))
        supportFragmentManager.registerFragmentLifecycleCallbacks(pageCallbacks, false)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawers()
            } else if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        binding.fab.setOnClickListener {
            if (!hasProfiles && !DataStore.serviceState.canStop) {
                (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)?.showAddNodeSheet()
                return@setOnClickListener
            }
            if (DataStore.serviceState.canStop) SagerNet.stopService() else connect.launch(
                null
            )
        }
        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)
        syncPageControls(supportFragmentManager.findFragmentById(R.id.fragment_holder))
        changeState(BaseService.State.Idle)
        refreshProfileAvailability()
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }


        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            onMainDispatcher { alert("A subscription URL is required").show() }
            return
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        group.name = group.name.takeIf { !it.isNullOrBlank() }
            ?: ("Subscription #" + System.currentTimeMillis())

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.ui_import) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.ui_import) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: Profile) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(getString(R.string.ui_import_next)).show()
        }
    }

    private fun onNavigationItemSelected(@IdRes id: Int): Boolean {
        if (navigation.checkedItemId == id) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(id)
        }
        return true
    }


    fun addNodeToGroup(groupId: Long) {
        DataStore.selectedGroup = groupId
        displayFragment(ConfigurationFragment().apply {
            arguments = Bundle().apply { putBoolean("openAddNode", true) }
        })
    }

    private fun displayFragment(fragment: ToolbarFragment) {
        syncPageControls(fragment)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commit()
        binding.drawerLayout.closeDrawers()
    }

    private fun syncPageControls(fragment: Fragment?) {
        if (fragment == null || !::binding.isInitialized) return
        val showControls = (fragment is ConfigurationFragment || DataStore.showBottomBar) &&
            (hasProfiles || renderedState.canStop)
        binding.stats.allowShow = showControls
        connection.updateConnectionId(TrafficObservation.connectionId(activityStarted, showControls))
        binding.fab.pageAllowsControls = showControls
        if (showControls) binding.fab.show() else binding.fab.hide()
        updateConnectionSummary(showControls)
        val id = when (fragment) {
            is ConfigurationFragment -> R.id.nav_configuration
            is GroupFragment -> R.id.nav_group
            is RouteFragment -> R.id.nav_route
            is SettingsFragment -> R.id.nav_settings
            is AboutFragment -> R.id.nav_about
            else -> return
        }
        navigation.setCheckedItem(id)
    }

    override fun onPostResume() {
        super.onPostResume()
        val requestedPage = pendingPage
        pendingPage = null
        if (requestedPage != null) displayFragmentWithId(requestedPage)
        else syncPageControls(supportFragmentManager.findFragmentById(R.id.fragment_holder))
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        if (supportFragmentManager.isStateSaved) {
            pendingPage = id
            return true
        }
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment())
            }

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        navigation.setCheckedItem(id)
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        val previousState = renderedState
        renderedState = state
        DataStore.serviceState = state
        binding.fab.changeState(state, previousState, animate)
        binding.stats.changeState(state)
        syncPageControls(supportFragmentManager.findFragmentById(R.id.fragment_holder))
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) {
                anchorView = binding.fab
            }
            // TODO
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(data)
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        binding.stats.invalidateConnectionTest()
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SHOW_BOTTOM_BAR -> runOnUiThread {
                syncPageControls(supportFragmentManager.findFragmentById(R.id.fragment_holder))
            }
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        activityStarted = true
        super.onStart()
        syncPageControls(supportFragmentManager.findFragmentById(R.id.fragment_holder))
    }

    override fun onStop() {
        activityStarted = false
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(pageCallbacks)
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
