package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean

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
            // Preserve the legacy last-entity match, but remove orphaned duplicate-name rows.
            val byName = old.associateBy { it.displayName() }
            val retainedIds = names.mapNotNull { byName[it]?.id }.toSet()
            val removed = old.filter { it.id !in retainedIds }
            val added = ArrayList<String>()
            val updated = LinkedHashMap<String, String>()
            val replacements = ArrayList<ProxyEntity>()
            proxies.forEachIndexed { index, bean ->
                val name = names[index]
                val order = index + 1L
                val entity = byName[name]
                if (entity == null) {
                    dao.addProxy(ProxyEntity(groupId = group.id, userOrder = order).apply { putBean(bean) })
                    added.add(name)
                } else {
                    val previous = entity.requireBean()
                    bean.customOutboundJson = previous.customOutboundJson
                    bean.customConfigJson = previous.customConfigJson
                    val contentChanged = previous != bean
                    if (contentChanged || entity.userOrder != order) {
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
