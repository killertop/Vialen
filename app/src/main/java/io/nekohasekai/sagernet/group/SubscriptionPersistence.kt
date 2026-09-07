package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.rust.RustBridge

/** Applies prepared, uniquely named subscription Beans. Network work stays outside the transaction. */
internal object SubscriptionPersistence {
    data class Result(val changed: Int, val added: List<String>, val updated: Map<String, String>, val deleted: List<String>)

    fun apply(db: SagerDatabase, group: ProxyGroup, proxies: List<AbstractBean>): Result {
        val names = proxies.map { it.displayName() }
        require(names.toSet().size == names.size) { "Subscription names must be disambiguated before persistence" }
        var result: Result? = null
        db.runInTransaction {
            check(db.groupDao().getById(group.id) != null) { "Subscription group was deleted during refresh" }
            val dao = db.proxyDao()
            val old = dao.getByGroup(group.id)
            val plan = RustBridge.planSubscription(
                old.map { it.displayName() },
                Array(old.size) { KryoConverters.subscriptionContent(old[it].requireBean()) },
                old.map { it.userOrder }.toLongArray(), names,
                Array(proxies.size) { KryoConverters.subscriptionContent(proxies[it]) }
            )
            val removed = plan.removedIndices.map { old[it] }
            val added = ArrayList<String>()
            val updated = LinkedHashMap<String, String>()
            val replacements = ArrayList<ProxyEntity>()
            proxies.forEachIndexed { index, bean ->
                val name = names[index]
                val order = index + 1L
                val oldIndex = plan.oldIndices[index]
                val entity = if (oldIndex == -1) null else old[oldIndex]
                if (entity == null) {
                    dao.addProxy(ProxyEntity(groupId = group.id, userOrder = order).apply { putBean(bean) })
                    added.add(name)
                } else {
                    val previous = entity.requireBean()
                    bean.customOutboundJson = previous.customOutboundJson
                    bean.customConfigJson = previous.customConfigJson
                    val contentChanged = plan.flags[index] and 1 != 0
                    if (plan.flags[index] != 0) {
                        entity.putBean(bean)
                        entity.userOrder = order // Content changes must not suppress reorder.
                        replacements.add(entity)
                        if (contentChanged) updated[name] = name
                    }
                }
            }
            dao.updateProxy(replacements)
            dao.deleteProxy(removed)
            check(dao.countByGroup(group.id) == proxies.size.toLong()) { "Subscription row count mismatch" }
            db.groupDao().updateGroup(group)
            result = Result(removed.size + added.size + updated.size, added, updated, removed.map { it.displayName() })
        }
        return checkNotNull(result)
    }
}
