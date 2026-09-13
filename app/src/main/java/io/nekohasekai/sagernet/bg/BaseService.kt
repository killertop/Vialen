package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.runCancellableUrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

class BaseService {

    companion object {
        // A new Service object cannot prove that a failed native close completed.
        // Keep this gate for the background process lifetime, as with the VPN stop gate.
        @Volatile internal var cleanupFailure: String? = null
    }

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        internal var recovery: ConnectionRecovery? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload(intent.getBooleanExtra("forceRestart", false))
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (SagerNet.power.isDeviceIdleMode) {
                        recovery?.idleChanged(true)
                        proxy?.box?.sleep()
                    } else {
                        proxy?.box?.wake()
                        recovery?.idleChanged(false)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> recovery?.screenChanged(false)
                Intent.ACTION_SCREEN_ON -> recovery?.screenChanged(true)

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            proxy?.looper?.onConsumersChanged()
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
                if (callback != null) callbackIdMap.remove(callback)
                data?.proxy?.looper?.onConsumersChanged()
            }
        }

        val callbackIdMap = java.util.concurrent.ConcurrentHashMap<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun setDiagnosticMode(enabled: Boolean) = Libcore.setDiagnosticMode(enabled)
        override fun getDiagnosticRemainingMillis(): Long = Libcore.diagnosticRemainingMillis()

        override fun clearTraffic(groupId: Long): Boolean = runBlocking {
            withContext(Dispatchers.Main.immediate) {
                val current = data ?: return@withContext false
                when (current.state) {
                    State.Stopped, State.Idle -> {
                        val ids = ProfileManager.clearTraffic(groupId)
                        current.binder.broadcast { callback ->
                            ids.forEach { callback.cbTrafficUpdate(io.nekohasekai.sagernet.aidl.TrafficData(it)) }
                        }
                        true
                    }
                    State.Connected -> current.proxy?.looper?.clearTraffic(groupId) ?: false
                    else -> false // Do not race a launch or final accounting flush.
                }
            }
        }

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
            data?.proxy?.looper?.onConsumersChanged()
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
            data?.proxy?.looper?.onConsumersChanged()
        }

        override fun urlTest(): Int {
            if (data?.proxy?.box == null) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, 3000
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        override fun close() {
            callbacks.kill()
            callbackIdMap.clear()
            data?.proxy?.looper?.onConsumersChanged()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload(forceRestart: Boolean = false) {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val reusable = try {
                if (forceRestart) {
                    val candidate = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                        ?: error("Missing selected profile")
                    ProxyInstance(candidate).buildConfigTmp()
                    false
                } else canReloadSelector()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A candidate must compile before the live instance is touched. Do not log
                // compiler messages, which may contain user configuration or credentials.
                Toast.makeText(this as Context, R.string.reload_invalid_config, Toast.LENGTH_LONG).show()
                return
            }
            if (reusable) {
                val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                if (tag.isNotBlank() && ent != null) {
                    // The native wrapper updates accounting and UI through its selection callback.
                    data.proxy!!.box.selectOutbound(tag)
                    return
                }
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun canReloadSelector(): Boolean {
            val running = data.proxy ?: return false
            if (data.state != State.Connected || !running.isInitialized()) return false
            if ((data.proxy?.config?.selectorGroupId ?: -1L) < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (running.platformConfig != tmpBox.platformConfig) return false
            return SelectorReloadPolicy.canReuse(
                running.lastSelectorGroupId, tmpBox.lastSelectorGroupId,
                running.config.config, tmpBox.config.config,
                running.config.profileTagMap, tmpBox.config.profileTagMap, ent.id,
            )
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses() {
            var failure: Throwable? = null
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
            // A recovery shutdown failure must not skip the remaining resource cleanup.
            try {
                data.recovery?.let { it.stop(); it.join() }
            } catch (error: Throwable) {
                retain(error)
            } finally {
                data.recovery = null
            }
            try {
                data.proxy?.close()
            } catch (error: Throwable) {
                retain(error)
            }
            try {
                wakeLock?.release()
            } catch (error: Throwable) {
                retain(error)
            } finally {
                wakeLock = null
            }
            try {
                DefaultNetworkListener.stop(this)
            } catch (cancelled: CancellationException) {
                retain(cancelled)
            } catch (error: Exception) {
                Logs.w("Network listener stop failed")
                Logs.w(error)
                failure?.let { if (it !== error) it.addSuppressed(error) }
            }
            failure?.let { throw it }
        }

        fun stopError(): String? = null

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            val switchService = data.proxy?.platformConfig?.serviceMode?.let {
                it != DataStore.serviceMode
            } == true
            DataStore.baseService = null
            DataStore.vpnService = null

            if (data.state == State.Stopping) return
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                withContext(NonCancellable) {
                    var failure: Throwable? = null
                    suspend fun attempt(action: suspend () -> Unit) {
                        try {
                            action()
                        } catch (error: Throwable) {
                            val previous = failure
                            if (previous == null) failure = error
                            else if (previous !== error) previous.addSuppressed(error)
                            cleanupFailure = getString(R.string.service_cleanup_failed)
                        }
                    }
                    attempt { data.notification?.destroy() }
                    data.notification = null
                    attempt { data.recovery?.stop() }
                    attempt { data.connectingJob?.cancelAndJoin() }
                    data.connectingJob = null
                    attempt { killProcesses() }
                    attempt {
                        if (data.closeReceiverRegistered) unregisterReceiver(data.receiver)
                    }
                    data.closeReceiverRegistered = false
                    data.proxy = null

                    var stopIssue: String? = null
                    attempt { stopIssue = stopError() }
                    fun message() = listOfNotNull(msg, stopIssue, cleanupFailure)
                        .distinct().joinToString("\n").ifEmpty { null }
                    attempt { data.changeState(State.Stopped, message()) }
                    var restarted = false
                    if (restart && stopIssue == null && cleanupFailure == null && failure == null) {
                        attempt {
                            if (switchService) {
                                // A mode change must start the selected service, not the old class.
                                // startService also handles missing Android VPN consent.
                                stopSelf()
                                SagerNet.startService()
                            } else startRunner()
                            restarted = true
                        }
                    }
                    if (!restarted) attempt { stopSelf() }
                    if (failure != null) {
                        // Also report a failure in the last restart/stop step to bound clients.
                        attempt { data.changeState(State.Stopped, message()) }
                        Logs.w(checkNotNull(failure))
                    }
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            val recovery = ConnectionRecovery(
                context = Dispatchers.Main.immediate,
                now = { SystemClock.elapsedRealtime() },
                probe = {
                    val box = data.proxy?.box
                    val target = ConnectionRecovery.probeTarget(DataStore.connectionTestURL)
                    if (data.state != State.Connected || box == null || target == null) {
                        ConnectionRecovery.Health.Unavailable
                    } else {
                        val session = Libcore.newUrlTestSession()
                        try {
                            runCancellableUrlTest(
                                cancel = { session.cancel() }, initialize = {}, start = {},
                                test = { session.run(box, target, ConnectionRecovery.PROBE_TIMEOUT_MS) },
                                close = {}, // The service owns this box; never close it from a probe.
                            )
                            ConnectionRecovery.Health.Healthy
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ConnectionRecovery.Health.Failed
                        }
                    }
                },
                reset = { withContext(Dispatchers.IO) { Libcore.resetAllConnections(true) } },
                networkEnabled = { DataStore.networkChangeResetConnections },
                wakeEnabled = { DataStore.wakeResetConnections },
                report = { event ->
                    Logs.d("ConnectionRecovery ${event.kind} reason=${event.reason} " +
                        "health=${event.health} resetCount=${event.resetCount}")
                },
            )
            data.recovery = recovery
            recovery.screenChanged(SagerNet.power.isInteractive)
            recovery.idleChanged(SagerNet.power.isDeviceIdleMode)
            DefaultNetworkListener.start(this) {
                val network = it
                runOnMainDispatcher {
                    // A queued callback from an earlier service lifetime must not change the new one.
                    if (data.recovery !== recovery || data.state == State.Stopping ||
                        data.state == State.Stopped) return@runOnMainDispatcher
                    val link = network?.let { SagerNet.connectivity.getLinkProperties(it) }
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link?.interfaceName) {
                        upstreamInterfaceName = link?.interfaceName
                    }
                    if (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName) {
                        Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                    }
                    recovery.networkChanged(network?.let {
                        ConnectionRecovery.NetworkIdentity(it.networkHandle, link?.interfaceName)
                    })
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            cleanupFailure?.let { message ->
                data.notification = createNotification("")
                stopRunner(false, message)
                return Service.START_NOT_STICKY
            }
            ProfileManager.selectFirstIfNeeded(DataStore.pendingSelectionGroup.takeIf { it > 0 }
                ?: DataStore.selectedGroup)
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            // Publish the job before its first instruction so stopRunner can always join startup.
            val connectingJob = GlobalScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    startProcesses()
                    currentCoroutineContext().ensureActive()
                    data.changeState(State.Connected)
                    data.recovery?.connected()

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    if (data.connectingJob === currentCoroutineContext()[Job]) {
                        data.connectingJob = null
                    }
                }
            }
            data.connectingJob = connectingJob
            connectingJob.start()
            return Service.START_NOT_STICKY
        }
    }

}
