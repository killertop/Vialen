package io.nekohasekai.sagernet.group

import androidx.room.withTransaction
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.ArrayDeque

internal object SubscriptionPersistence {
    data class Result(val changed: Int, val added: List<String>, val updated: Map<String, String>, val deleted: List<String>)
    data class Existing(val sourceKey: String, val profile: Profile)

    fun sourceKey(profile: Profile): String = if (profile.id.isNotBlank()) "id:${profile.id}" else
        "name:${profile.type.length}:${profile.type}${profile.name.length}:${profile.name}"

    /** Reserve exact matches first so reordering duplicate names cannot exchange local IDs. */
    fun match(old: List<Existing>, incoming: List<Profile>): List<Int?> {
        val exact = HashMap<Pair<String, Profile>, ArrayDeque<Int>>()
        old.forEachIndexed { index, entry ->
            exact.getOrPut(entry.sourceKey to SubscriptionDedup.semanticKey(entry.profile)) { ArrayDeque() }.add(index)
        }
        val used = BooleanArray(old.size)
        val result = incoming.map { profile ->
            exact[sourceKey(profile) to SubscriptionDedup.semanticKey(profile)]?.pollFirst()?.also { used[it] = true }
        }.toMutableList()
        val remaining = HashMap<String, ArrayDeque<Int>>()
        old.forEachIndexed { index, entry ->
            if (!used[index]) remaining.getOrPut(entry.sourceKey) { ArrayDeque() }.add(index)
        }
        incoming.forEachIndexed { index, profile ->
            if (result[index] == null) result[index] = remaining[sourceKey(profile)]?.pollFirst()
        }
        return result
    }

    suspend fun apply(db: SagerDatabase, ticket: SubscriptionRefresh.Ticket, proxies: List<Profile>,
        remoteUserinfo: String? = null, remoteName: String? = null): Result {
        require(proxies.isNotEmpty()) { "Empty subscription cannot replace saved profiles" }
        currentCoroutineContext().ensureActive()
        return db.withTransaction {
            currentCoroutineContext().ensureActive()
            val group = SubscriptionRefresh.requireCurrent(db, ticket)
            val dao = db.proxyDao()
            val old = dao.getByGroup(group.id)
            val previous = old.map { Existing(it.sourceKey, it.requireProfile()) }
            val matches = match(previous, proxies)
            val retained = matches.filterNotNull().toSet()
            val removed = old.filterIndexed { index, _ -> index !in retained }
            val names = SubscriptionNames.unique(proxies.map(SubscriptionNames::display))
            val oldNames = SubscriptionNames.unique(previous.map { SubscriptionNames.display(it.profile) })
            val added = ArrayList<String>()
            val updated = LinkedHashMap<String, String>()
            proxies.forEachIndexed { index, profile ->
                currentCoroutineContext().ensureActive()
                val oldIndex = matches[index]
                if (oldIndex == null) {
                    dao.addProxy(ProxyEntity(groupId = group.id, userOrder = index + 1L,
                        sourceKey = sourceKey(profile)).putProfile(profile.copy(id = "")))
                    added.add(names[index])
                } else {
                    val saved = previous[oldIndex].profile
                    val replacement = profile.copy(id = saved.id)
                    val entity = old[oldIndex]
                    if (saved != replacement) updated[oldNames[oldIndex]] = names[index]
                    if (saved != replacement || entity.userOrder != index + 1L) {
                        dao.updateProxy(entity.copy(userOrder = index + 1L, sourceKey = sourceKey(profile)).putProfile(replacement))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            dao.deleteProxy(removed)
            check(dao.countByGroup(group.id) == proxies.size.toLong()) { "Subscription row count mismatch" }
            // Merge only fields owned by refresh into the freshly loaded row.
            remoteUserinfo?.let { group.subscription!!.subscriptionUserinfo = it }
            group.subscription!!.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            if (group.name?.startsWith("Subscription #") == true) remoteName?.let { group.name = it }
            db.groupDao().updateGroup(group)
            currentCoroutineContext().ensureActive()
            Result(removed.size + added.size + updated.size, added, updated,
                previous.indices.filter { it !in retained }.map { oldNames[it] })
        }
    }
}
