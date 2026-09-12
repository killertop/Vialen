package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LinkProperties
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.utils.VpnNetworkLifecycle
import io.nekohasekai.sagernet.utils.VpnStopGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.InetAddress
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        private val stopGate = VpnStopGate()
        private val removalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

    }

    var conn: ParcelFileDescriptor? = null

    private var metered = false
    private var networkLifecycle: VpnNetworkLifecycle? = null
    private var lastStopError: String? = null

    override fun stopError(): String? = lastStopError

    private data class LinkExpectation(
        val mtu: Int,
        val addresses: Set<Pair<InetAddress, Int>>,
        val routes: Set<Pair<InetAddress, Int>>,
        val dns: InetAddress,
    )

    @Volatile private var linkExpectation: LinkExpectation? = null

    private fun matchesVpnLink(link: LinkProperties): Boolean {
        val expected = linkExpectation ?: return false
        return !link.interfaceName.isNullOrEmpty() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || link.mtu == expected.mtu) &&
            link.linkAddresses.map { it.address to it.prefixLength }.containsAll(expected.addresses) &&
            link.dnsServers.contains(expected.dns) &&
            link.routes.map { it.destination.address to it.destination.prefixLength }.containsAll(expected.routes)
    }

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        stopGate.checkCanStart()
        lastStopError = null
        linkExpectation = null
        val lifecycle = VpnNetworkLifecycle(SagerNet.connectivity, ::matchesVpnLink)
        networkLifecycle = lifecycle
        lifecycle.begin()
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance and establish TUN
        val established = checkNotNull(conn) { "VPN interface was not established" }
        lifecycle.awaitReady()
        currentCoroutineContext().ensureActive()
        check(conn === established && prepare(this) == null) { "VPN ownership changed during startup" }
    }

    override var wakeLock: PowerManager.WakeLock? = null

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:vpn")
            .apply { acquire() }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses() {
        val lifecycle = networkLifecycle
        var failure: Throwable? = null
        var pendingRemoval = false
        fun retain(error: Throwable) {
            val previous = failure
            if (previous == null) failure = error
            else if (previous !== error) {
                if (error is CancellationException && previous !is CancellationException) {
                    error.addSuppressed(previous)
                    failure = error
                } else previous.addSuppressed(error)
            }
        }
        try {
            try {
                conn?.close()
            } catch (error: Throwable) {
                retain(error)
            } finally {
                conn = null
            }
            // The core owns a duplicate TUN descriptor; close it before waiting for onLost.
            try {
                super.killProcesses()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                retain(error)
            }
            if (lifecycle != null && !lifecycle.closeAndConfirm()) {
                currentCoroutineContext().ensureActive()
                if (failure == null) pendingRemoval = true
                else retain(IllegalStateException("VPN network removal was not confirmed"))
            }
        } catch (cancelled: CancellationException) {
            retain(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            retain(error)
        } finally {
            var handedOff = false
            try {
                if (pendingRemoval && failure == null && lifecycle != null) {
                    stopGate.waitForRemoval(
                        removalScope,
                        lifecycle,
                        lifecycle::awaitRemoval,
                        lifecycle::dispose,
                    ) { Logs.w(it) }
                    handedOff = true
                    lastStopError = "Waiting for the system to remove the previous VPN. Try connecting again after cleanup completes."
                }
            } catch (error: Throwable) {
                retain(error)
            }
            if (!handedOff) {
                try {
                    lifecycle?.dispose()
                } catch (error: Throwable) {
                    retain(error)
                }
            }
            networkLifecycle = null
            linkExpectation = null
            failure?.let {
                stopGate.markFailed()
                BaseService.cleanupFailure = getString(R.string.service_cleanup_failed)
                lastStopError = "VPN cleanup could not be confirmed. Restart the app before reconnecting."
                Logs.w(it)
            }
        }
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    fun startVpn(tunOptionsJson: String, tunPlatformOptionsJson: String): Int {
//        Logs.d(tunOptionsJson)
//        Logs.d(tunPlatformOptionsJson)
//        val tunOptions = JSONObject(tunOptionsJson)

        // address & route & MTU ...... use NB4A GUI config
        val mtu = DataStore.mtu
        val builder = Builder().setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(mtu)
        val expectedAddresses = linkedSetOf<Pair<InetAddress, Int>>()
        val expectedRoutes = linkedSetOf<Pair<InetAddress, Int>>()
        fun addAddress(address: String, prefix: Int) {
            builder.addAddress(address, prefix)
            expectedAddresses.add(InetAddress.getByName(address) to prefix)
        }
        fun addRoute(address: String, prefix: Int) {
            builder.addRoute(address, prefix)
            expectedRoutes.add(InetAddress.getByName(address) to prefix)
        }
        val ipv6Mode = DataStore.ipv6Mode

        // address
        addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (ipv6Mode != IPv6Mode.DISABLE) {
            addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }
        builder.addDnsServer(PRIVATE_VLAN4_ROUTER)

        // route
        if (DataStore.bypassLan) {
            resources.getStringArray(R.array.bypass_private_route).forEach {
                val subnet = Subnet.fromString(it)!!
                addRoute(subnet.address.hostAddress!!, subnet.prefixSize)
            }
            addRoute(PRIVATE_VLAN4_ROUTER, 32)
            addRoute(FAKEDNS_VLAN4_CLIENT, 15)
            // https://issuetracker.google.com/issues/149636790
            if (ipv6Mode != IPv6Mode.DISABLE) {
                addRoute("2000::", 3)
            }
        } else {
            addRoute("0.0.0.0", 0)
            if (ipv6Mode != IPv6Mode.DISABLE) {
                addRoute("::", 0)
            }
        }

        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)

        // app route
        val packageName = packageName
        val proxyApps = DataStore.proxyApps
        var bypass = DataStore.bypass
        val workaroundSYSTEM = false /* DataStore.tunImplementation == TunImplementation.SYSTEM */
        val needBypassRootUid = workaroundSYSTEM || data.proxy!!.config.trafficMap.values.any {
            it[0].hysteriaBean?.protocol == HysteriaBean.PROTOCOL_FAKETCP
        }

        if (proxyApps || needBypassRootUid) {
            val individual = mutableSetOf<String>()
            val allApps by lazy {
                packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
                    when (it.packageName) {
                        packageName -> false
                        "android" -> true
                        else -> it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
                    }
                }.map {
                    it.packageName
                }
            }
            if (proxyApps) {
                individual.addAll(DataStore.individual.split('\n').filter { it.isNotBlank() })
                if (bypass && needBypassRootUid) {
                    val individualNew = allApps.toMutableList()
                    individualNew.removeAll(individual)
                    individual.clear()
                    individual.addAll(individualNew)
                    bypass = false
                }
            } else {
                individual.addAll(allApps)
                bypass = false
            }

            val added = mutableListOf<String>()

            individual.apply {
                // Allow Matsuri itself using VPN.
                remove(packageName)
                if (!bypass) add(packageName)
            }.forEach {
                try {
                    if (bypass) {
                        builder.addDisallowedApplication(it)
                    } else {
                        builder.addAllowedApplication(it)
                    }
                    added.add(it)
                } catch (ex: PackageManager.NameNotFoundException) {
                    Logs.w(ex)
                }
            }

            if (bypass) {
                Logs.d("Add bypass: ${added.joinToString(", ")}")
            } else {
                Logs.d("Add allow: ${added.joinToString(", ")}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DataStore.appendHttpProxy) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOCALHOST, DataStore.mixedPort))
        }

        metered = DataStore.meteredNetwork
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)
        linkExpectation = LinkExpectation(mtu, expectedAddresses.toSet(), expectedRoutes.toSet(),
            InetAddress.getByName(PRIVATE_VLAN4_ROUTER))
        conn = builder.establish() ?: throw NullConnectionException()
        checkNotNull(networkLifecycle) { "VPN lifecycle was not registered" }.markEstablished()

        return conn!!.fd
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        SagerNet.underlyingNetwork?.let {
            builder?.setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
                ?: setUnderlyingNetworks(arrayOf(SagerNet.underlyingNetwork))
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}
