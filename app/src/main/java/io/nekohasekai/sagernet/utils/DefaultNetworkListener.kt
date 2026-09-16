package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.LinkProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage() {
            val response = CompletableDeferred<Boolean>()
        }

        class Put(val network: Network, val source: Callback) : NetworkMessage()
        class Update(val network: Network, val source: Callback) : NetworkMessage()
        class Lost(val network: Network, val source: Callback) : NetworkMessage()
    }

    // Connectivity callbacks must never wait for the actor. Keep every event
    // ordered while moving actor/listener work off ConnectivityThread.
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkActor = Channel<NetworkMessage>(Channel.UNLIMITED)

    init {
        networkScope.launch {
            val listeners = mutableMapOf<Any, (Network?) -> Unit>()
            var network: Network? = null
            val pendingRequests = arrayListOf<NetworkMessage.Get>()
            fun notifyListener(listener: (Network?) -> Unit, current: Network?) {
                try {
                    listener(current)
                } catch (error: Throwable) {
                    // A consumer callback must not terminate the process-wide actor and
                    // permanently strand later start/get/stop calls.
                    Logs.w("Default network listener callback failed", error)
                }
            }
            for (message in networkActor) when (message) {
                is NetworkMessage.Start -> {
                    if (listeners.isEmpty()) register()
                    listeners[message.key] = message.listener
                    if (network != null) notifyListener(message.listener, network)
                }
                is NetworkMessage.Get -> {
                    if (listeners.isEmpty()) {
                        message.response.completeExceptionally(
                            IllegalStateException("Getting network without any listeners is not supported")
                        )
                    } else if (fallback) {
                        val active = SagerNet.connectivity.activeNetwork
                        if (active == null) {
                            message.response.completeExceptionally(UnknownHostException())
                        } else {
                            message.response.complete(active)
                        }
                    } else if (network == null) {
                        pendingRequests += message
                    } else {
                        message.response.complete(network)
                    }
                }
                is NetworkMessage.Stop -> {
                    try {
                        val removed = listeners.remove(message.key) != null
                        if (removed && listeners.isEmpty()) {
                            network = null
                            pendingRequests.forEach { it.response.cancel() }
                            pendingRequests.clear()
                            unregister()
                        }
                        message.response.complete(removed)
                    } catch (error: Throwable) {
                        message.response.completeExceptionally(error)
                    }
                }

                is NetworkMessage.Put -> if (message.source === registeredCallback && listeners.isNotEmpty()) {
                    network = message.network
                    pendingRequests.forEach { it.response.complete(message.network) }
                    pendingRequests.clear()
                    listeners.values.forEach { notifyListener(it, network) }
                }
                is NetworkMessage.Update -> if (message.source === registeredCallback && network == message.network) {
                    listeners.values.forEach { notifyListener(it, network) }
                }
                is NetworkMessage.Lost -> if (message.source === registeredCallback && network == message.network) {
                    network = null
                    listeners.values.forEach { notifyListener(it, null) }
                }
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get() = NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any): Boolean = NetworkMessage.Stop(key).run {
        networkActor.send(this)
        response.await()
    }

    private fun post(message: NetworkMessage) {
        networkActor.trySend(message)
    }

    // These callbacks run on ConnectivityThread; posting is deliberately non-blocking.
    private class Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            post(NetworkMessage.Put(network, this@Callback))

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            post(NetworkMessage.Update(network, this@Callback))
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            post(NetworkMessage.Update(network, this@Callback))
        }

        override fun onLost(network: Network) =
            post(NetworkMessage.Lost(network, this@Callback))
    }

    private var registeredCallback: Callback? = null
    private var fallback = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        val callback = Callback()
        registeredCallback = callback
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, callback, mainHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, callback, mainHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback, mainHandler)
                }
                else -> { // Android 7.x; minSdk is 24.
                    SagerNet.connectivity.registerDefaultNetworkCallback(callback)
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
            registeredCallback = null
        }
    }

    private fun unregister() {
        val callback = registeredCallback
        registeredCallback = null // Reject already queued callbacks, including across a later registration.
        // Registration failures use the fallback network and have no callback to remove.
        if (!fallback && callback != null) SagerNet.connectivity.unregisterNetworkCallback(callback)
    }
}
