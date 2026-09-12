package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
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
        profile.id = SagerDatabase.proxyDao.addProxy(profile)
        selectFirstIfNeeded(groupId)
        iterator { onAdd(profile) }
        return profile
    }

    /**
     * Imports must not leave profiles behind if the destination group is deleted while a
     * multi-profile document is being parsed. Keep the existence check and every row write in
     * one database transaction; listener callbacks deliberately run after commit.
     */
    suspend fun createProfilesForImport(groupId: Long, beans: List<AbstractBean>): List<ProxyEntity> {
        if (beans.isEmpty()) return emptyList()
        val profiles = SagerDatabase.instance.runInTransaction<List<ProxyEntity>> {
            checkNotNull(SagerDatabase.groupDao.getById(groupId)) {
                app.getString(R.string.profile_import_target_missing)
            }
            var nextOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1L
            beans.map { bean ->
                bean.applyDefaultValues()
                ProxyEntity(groupId = groupId).apply {
                    id = 0
                    putBean(bean)
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
        if (DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Idle &&
            DataStore.serviceState != io.nekohasekai.sagernet.bg.BaseService.State.Stopped) return
        val selected = DataStore.selectedProxy
        if (selected != 0L && SagerDatabase.proxyDao.getById(selected) != null) return
        val first = SagerDatabase.proxyDao.getIdsByGroup(groupId).firstOrNull() ?: return
        DataStore.selectProxyIfUnchanged(selected, first)
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

    suspend fun clearTraffic(groupId: Long) {
        val ids = SagerDatabase.instance.runInTransaction<List<Long>> {
            val ids = SagerDatabase.proxyDao.getIdsByGroup(groupId)
            if (ids.isNotEmpty()) SagerDatabase.proxyDao.clearTraffic(ids)
            ids
        }
        ids.forEach { postUpdate(TrafficData(id = it)) }
    }

    suspend fun updateProfile(profile: ProxyEntity) {
        SagerDatabase.proxyDao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
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
            DataStore.rulesFirstCreate = true
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_quic),
                    port = "443",
                    network = "udp",
                    outbound = -2
                )
            )
            createRule(
                RuleEntity(
                    name = app.getString(R.string.route_opt_block_ads),
                    ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official("geosite", "category-ads-all", app.getString(R.string.route_set_ads)))),
                    outbound = -2
                )
            )
            val fuckedCountry = mutableListOf("cn:中国")
            if (Locale.getDefault().country != Locale.CHINA.country) {
                // 非中文用户
                fuckedCountry += "ir:Iran"
                fuckedCountry += "ru:Russia"
            }
            for (c in fuckedCountry) {
                val country = c.substringBefore(":")
                val displayCountry = c.substringAfter(":")
                //
                if (country == "cn") createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_play_store, displayCountry),
                        domains = "googleapis.cn",
                    ), false
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_domain, displayCountry),
                        ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official("geosite", country, displayCountry))),
                        outbound = -1
                    ), false
                )
                createRule(
                    RuleEntity(
                        name = app.getString(R.string.route_bypass_ip, displayCountry),
                        ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official("geoip", country, displayCountry))),
                        outbound = -1
                    ), false
                )
            }
            rules = SagerDatabase.rulesDao.allRules()
        }
        return rules
    }

}
