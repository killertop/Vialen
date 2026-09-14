package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.core.CoreClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
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

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        if (profile.type != ProxyEntity.TYPE_CHAIN && profile.type != ProxyEntity.TYPE_CONFIG) CoreClient.validate(profile.requireProfile())
        currentCoroutineContext().ensureActive()
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        selectFirstIfNeeded(groupId)
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun createProfile(groupId: Long, profile: Profile): ProxyEntity =
        createProfilesForImport(groupId, listOf(profile)).single()

    /**
     * Imports must not leave profiles behind if the destination group is deleted while a
     * multi-profile document is being parsed. Keep the existence check and every row write in
     * one database transaction; listener callbacks deliberately run after commit.
     */
    suspend fun createProfilesForImport(groupId: Long, nodes: List<Profile>): List<ProxyEntity> {
        if (nodes.isEmpty()) return emptyList()
        CoreClient.validateProfiles(nodes)
        val context = currentCoroutineContext()
        context.ensureActive()
        val profiles = SagerDatabase.instance.runInTransaction<List<ProxyEntity>> {
            checkNotNull(SagerDatabase.groupDao.getById(groupId)) {
                app.getString(R.string.profile_import_target_missing)
            }
            var nextOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1L
            nodes.map { node ->
                context.ensureActive()
                ProxyEntity(groupId = groupId).apply {
                    id = 0
                    putProfile(node.copy(id = ""))
                    userOrder = nextOrder++
                    id = SagerDatabase.proxyDao.addProxy(this)
                }
            }
        }
        for (profile in profiles) {
            selectFirstIfNeeded(groupId)
            iterator { onAdd(profile) }
        }
        return profiles
    }

    /** Use only the persisted import target; never borrow a node from another group. */
    @Synchronized
    fun selectFirstIfNeeded(groupId: Long) {
        val selected = DataStore.selectedProxy
        if (selected != 0L && SagerDatabase.proxyDao.getById(selected) != null) {
            DataStore.pendingSelectionGroup = 0
            return
        }
        if (DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Idle &&
            DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Stopped) {
            DataStore.pendingSelectionGroup = groupId
            return
        }
        val first = SagerDatabase.proxyDao.getIdsByGroup(groupId).firstOrNull() ?: return
        if (DataStore.selectProxyIfUnchanged(selected, first)) DataStore.pendingSelectionGroup = 0
    }

    /** Commit only new bytes. The caller must checkpoint success before notifying listeners. */
    fun addTraffic(delta: TrafficData): TrafficData? {
        if (delta.tx == 0L && delta.rx == 0L) return null
        val total = SagerDatabase.instance.runInTransaction<TrafficData?> {
            if (SagerDatabase.proxyDao.addTraffic(delta.id, delta.tx, delta.rx) == 0) null
            else SagerDatabase.proxyDao.getTraffic(delta.id)
        }
        return total
    }

    suspend fun clearTraffic(groupId: Long): List<Long> {
        val ids = SagerDatabase.instance.runInTransaction<List<Long>> {
            val ids = SagerDatabase.proxyDao.getIdsByGroup(groupId)
            if (ids.isNotEmpty()) SagerDatabase.proxyDao.clearTraffic(ids)
            ids
        }
        ids.forEach { postUpdate(TrafficData(id = it)) }
        return ids
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        if (profile.type != ProxyEntity.TYPE_CHAIN && profile.type != ProxyEntity.TYPE_CONFIG) CoreClient.validate(profile.requireProfile())
        currentCoroutineContext().ensureActive()
        SagerDatabase.instance.runInTransaction {
            val traffic = SagerDatabase.proxyDao.getTraffic(profile.id)
                ?: error("Profile no longer exists")
            profile.tx = traffic.tx
            profile.rx = traffic.rx
            SagerDatabase.proxyDao.updateProxy(profile)
        }
        iterator { onUpdated(profile, false) }
    }

    /** Persist a completed batch without stale profile fields or per-profile UI notifications. */
    suspend fun updateConnectionTestResults(results: List<ConnectionTestResult>) {
        if (results.isEmpty()) return
        SagerDatabase.proxyDao.updateConnectionTestResults(results)
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        SagerDatabase.proxyDao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    suspend fun deleteProfile2(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (SagerDatabase.proxyDao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
        if (SagerDatabase.proxyDao.countByGroup(groupId) > 1) {
            GroupManager.rearrange(groupId)
        }
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: TrafficData) {
        iterator {
            try {
                onUpdated(data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // UI callbacks must not abort the persistence queue or hide updates from peers.
                Logs.w(error)
            }
        }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        RouteRuleSet.validateRule(rule)
        rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
        rule.id = SagerDatabase.rulesDao.createRule(rule)
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        RouteRuleSet.validateRule(rule)
        SagerDatabase.rulesDao.updateRule(rule)
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    suspend fun getRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            // Insert the complete ordered preset atomically. Never replace an existing list:
            // old presets have no stable identity distinguishing them from user-authored rules.
            SagerDatabase.instance.runInTransaction {
                if (SagerDatabase.rulesDao.allRules().isEmpty()) {
                    SagerDatabase.rulesDao.insert(DefaultRouteRules.create { app.getString(it) })
                }
            }
            DataStore.rulesFirstCreate = true
            rules = SagerDatabase.rulesDao.allRules()
        }
        return rules
    }

}
