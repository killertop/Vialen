package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, message: String)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long) {
        val selected = DataStore.selectedProxy
        val removedSelection = SagerDatabase.instance.runInTransaction<Boolean> {
            val belongs = SagerDatabase.proxyDao.getById(selected)?.groupId == groupId
            SagerDatabase.proxyDao.deleteAll(groupId)
            belongs
        }
        if (removedSelection) DataStore.clearDeletedSelection(selected)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        SagerDatabase.proxyDao.rearrange(groupId)
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        group.applyDefaultValues()
        if (group.type == GroupType.SUBSCRIPTION) {
            checkNotNull(group.subscription).let {
                it.link = io.nekohasekai.sagernet.group.SubscriptionLink.normalize(it.link)
            }
        }
        group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
        group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
            io.nekohasekai.sagernet.group.GroupUpdater.startUpdate(group, true)
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        group.applyDefaultValues()
        SagerDatabase.instance.runInTransaction {
            val stored = SagerDatabase.groupDao.getById(group.id) ?: error("分组已删除")
            if (group.type == GroupType.SUBSCRIPTION) {
                val subscription = checkNotNull(group.subscription)
                val raw = subscription.link.trim()
                subscription.link = if (raw.startsWith("content://")) {
                    val uri = android.net.Uri.parse(raw)
                    require(stored.type == GroupType.SUBSCRIPTION && stored.subscription?.link == raw &&
                        io.nekohasekai.sagernet.SagerNet.application.contentResolver.persistedUriPermissions.any {
                            it.isReadPermission && it.uri == uri
                        }) { "文档授权已失效，请重新选择订阅文件" }
                    raw
                } else io.nekohasekai.sagernet.group.SubscriptionLink.normalize(raw)
                if (stored.type == GroupType.SUBSCRIPTION && stored.subscription?.link == subscription.link) {
                    subscription.lastUpdated = stored.subscription!!.lastUpdated
                    subscription.subscriptionUserinfo = stored.subscription!!.subscriptionUserinfo
                } else {
                    subscription.lastUpdated = 0
                    subscription.subscriptionUserinfo = ""
                }
            }
            SagerDatabase.groupDao.updateGroup(group)
        }
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.proxyDao.deleteByGroup(groupId)
            SagerDatabase.groupDao.deleteById(groupId)
        }
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        val groupIds = group.map { it.id }.toLongArray()
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.proxyDao.deleteByGroup(groupIds)
            SagerDatabase.groupDao.deleteGroup(group)
        }
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}
