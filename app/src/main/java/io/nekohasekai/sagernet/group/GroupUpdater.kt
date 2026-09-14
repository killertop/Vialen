package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(profiles: List<Profile>, groupId: Long?): List<Profile> = coroutineScope {
        val ipv6Mode = DataStore.ipv6Mode
        val limiter = Semaphore(5)
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        profiles.map { profile -> async(Dispatchers.IO) {
            limiter.withPermit {
                ensureActive()
                val resolved = if (profile.server.isIpAddress()) profile else try {
                    val network = SagerNet.underlyingNetwork
                    val addresses = if (network != null && DataStore.enableFakeDns &&
                        DataStore.serviceState.started && DataStore.serviceMode == Key.MODE_VPN) {
                        network.getAllByName(profile.server).toList()
                    } else InetAddress.getAllByName(profile.server).toList()
                    ensureActive()
                    val allowed = addresses.filter {
                        when (ipv6Mode) {
                            IPv6Mode.DISABLE -> it is Inet4Address
                            IPv6Mode.ONLY -> it !is Inet4Address
                            else -> true
                        }
                    }
                    rewriteAddress(profile, allowed, ipv6Mode >= IPv6Mode.PREFER)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    Logs.d("Lookup ${profile.server} failed: ${e.readableMessage}", e)
                    profile
                }
                ensureActive()
                progress.progress++
                if (groupId != null) GroupManager.postReload(groupId)
                resolved
            }
        } }.awaitAll()
    }

    internal fun rewriteAddress(profile: Profile, addresses: List<InetAddress>, ipv6First: Boolean): Profile {
        val address = addresses.sortedBy { (it is Inet4Address) == ipv6First }.firstOrNull()
            ?.hostAddress ?: error("No address for selected IP mode")
        val tls = profile.tls?.let {
            if (it.enabled && it.serverName.isBlank()) it.copy(serverName = profile.server) else it
        }
        return profile.copy(server = address, tls = tls)
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean = coroutineScope {
            if (!updating.add(proxyGroup.id)) return@coroutineScope false
            try {
                GroupManager.postReload(proxyGroup.id)
                val ticket = SubscriptionRefresh.begin(io.nekohasekai.sagernet.database.SagerDatabase.instance,
                    proxyGroup.id, requireAutoUpdate = !byUser)
                val subscription = checkNotNull(ticket.group.subscription)
                val userInterface = GroupManager.userInterface
                if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) &&
                    !DataStore.serviceState.connected) {
                    if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                        return@coroutineScope false
                    }
                }
                RawUpdater.refresh(ticket, userInterface, byUser)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                GroupManager.userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                false
            } finally {
                withContext(NonCancellable) { finishUpdate(proxyGroup) }
            }
        }

        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup.id)
        }

    }

}
