package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.rust.RustBridge
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.proxy.config.ConfigBean

/** IO boundary. Database records and settings are each read in one store transaction;
 * the stores have separate transaction domains. No IO occurs during generate().
 */
internal class ConfigSnapshot private constructor(
    private val wire: String,
    private val entities: Map<Long, ProxyEntity>,
    val ruleNames: Map<Long, String>,
    private val selected: Long,
) {
    data class Output(val result: ConfigBuildResult, val warnings: List<Pair<Long, String>>)
    fun generate(): Output {
        val decoded = RustBridge.generateConfig(wire.encodeToByteArray(throwOnInvalidSequence = true))
        val root = JsonParser.parseString(decoded.decodeToString(throwOnInvalidSequence = true)).asJsonObject
        check(root["version"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString() == "1" } == true) { "Invalid config result version" }
        check(root["status"]?.asString == "SUCCESS") {
            when (root["error"]?.asString) {
                "NO_DIRECT_DNS" -> "No direct DNS, check your settings!"
                "NO_REMOTE_DNS" -> "No remote DNS, check your settings!"
                "CYCLIC_OR_EXCESSIVE_CHAIN" -> "Proxy chain is cyclic or too deep"
                else -> root["error"]?.asString?.takeIf { it.startsWith("Rule ") } ?: "Configuration snapshot rejected"
            }
        }
        check(root.keySet() == setOf("version","status","config","traffic","tags","selector_group","generated","warnings"))
        // Recreate platform collection iteration exactly, including treeified
        // HashMap buckets and ProxyEntity's Bean-based hash. Ordering is observable
        // by selector callbacks and traffic accounting. Work on fresh output copies.
        val outputEntities = entities.mapValues { (_, row) ->
            row.copy().apply { putBean(copyBean(row.requireBean())) }
        }
        val traffic = HashMap<String, List<ProxyEntity>>()
        root["traffic"].asJsonObject.entrySet().forEach { (tag, ids) ->
            val rows = HashSet<ProxyEntity>()
            ids.asJsonArray.forEach { rows.add(checkNotNull(outputEntities[it.asLong]) { "Unknown traffic profile" }) }
            traffic[tag] = rows.toList()
        }
        val tags = HashMap<Long, String>()
        root["tags"].asJsonArray.forEach { entry ->
            check(entry.asJsonArray.size() == 2)
            val id = entry.asJsonArray[0].asLong
            check(outputEntities.containsKey(id)) { "Unknown tag profile" }
            tags[id] = entry.asJsonArray[1].asString
        }
        root["generated"].asJsonArray.forEach {
            val bean = checkNotNull(outputEntities[it.asLong]).requireBean()
            bean.finalAddress = bean.serverAddress; bean.finalPort = bean.serverPort
        }
        val warnings = root["warnings"].asJsonArray.map { entry ->
            check(entry.asJsonArray.size() == 2)
            entry.asJsonArray[0].asLong to entry.asJsonArray[1].asString
        }
        return Output(ConfigBuildResult(root["config"].asString, emptyList(), selected, traffic,
            tags, root["selector_group"].asLong), warnings)
    }
    override fun toString() = "ConfigSnapshot(v1)"

    companion object {
        private val gson = com.google.gson.GsonBuilder().serializeNulls().create()
        /** Only syntax conversion at the adapter: all merge semantics are Rust-owned. */
        private fun overlay(text: String?): JsonElement {
            if (text.isNullOrBlank()) return JsonNull.INSTANCE
            return JsonParser.parseString(text).also { require(it.isJsonObject) { "Custom configuration must be an object" } }
        }
        private fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
            for ((key, value) in fields) add(key, if (value is JsonElement) value else gson.toJsonTree(value))
        }
        private fun copyBean(bean: AbstractBean): AbstractBean {
            // Database deserialization reinitializes defaults (notably Hysteria protocol).
            // Snapshot the current fields exactly, without running that normalization again.
            val copy = bean.javaClass.getDeclaredConstructor().newInstance()
            for (field in bean.javaClass.fields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val value = field.get(bean)
                field.set(copy, if (value is List<*>) value.toMutableList() else value)
            }
            return copy
        }
        fun capture(proxy: ProxyEntity, forTest: Boolean, forExport: Boolean): ConfigSnapshot {
            val full = proxy.requireBean() as? ConfigBean
            if (full?.type == 0) {
                val wire = obj("mode" to "passthrough", "version" to 1, "selected" to proxy.id, "full_config" to full.config)
                val copy = proxy.copy().apply { putBean(copyBean(full)) }
                return ConfigSnapshot(wire.toString(), mapOf(proxy.id to copy), emptyMap(), proxy.id)
            }
            lateinit var settings: JsonObject
            PublicDatabase.instance.runInTransaction(Runnable {
                settings = obj(
                    "service_mode" to DataStore.serviceMode, "allow_access" to DataStore.allowAccess,
                    "remote_dns" to DataStore.remoteDns, "direct_dns" to DataStore.directDns,
                    "enable_dns_routing" to DataStore.enableDnsRouting, "fake_dns" to DataStore.enableFakeDns,
                    "sniffing" to DataStore.trafficSniffing, "ipv6" to DataStore.ipv6Mode,
                    "log_level" to DataStore.logLevel,
                    "tun" to DataStore.tunImplementation, "mtu" to DataStore.mtu, "mixed_port" to DataStore.mixedPort,
                    "resolve_destination" to DataStore.resolveDestination, "bypass_lan" to DataStore.bypassLanInCore,
                    "global_insecure" to DataStore.globalAllowInsecure,
                    "server_strategy" to moe.matsuri.nb4a.SingBoxOptionsUtil.domainStrategy("server"),
                    "custom" to if (forTest) JsonNull.INSTANCE else overlay(DataStore.globalCustomConfig),
                    "tun_v4" to VpnService.PRIVATE_VLAN4_CLIENT, "tun_v6" to VpnService.PRIVATE_VLAN6_CLIENT,
                )
            })
            val entities = LinkedHashMap<Long, ProxyEntity>()
            val pending = java.util.ArrayDeque<ProxyEntity>()
            val groups = LinkedHashMap<Long, ProxyGroup?>()
            var rules: List<RuleEntity> = emptyList()
            var selectorIds: List<Long> = emptyList()
            var extraIds: List<Long> = emptyList()
            SagerDatabase.instance.runInTransaction(Runnable {
                fun retain(entity: ProxyEntity) {
                    if (!entities.containsKey(entity.id)) {
                        val copy = entity.copy().apply { putBean(copyBean(entity.requireBean())) }
                        entities[entity.id] = copy
                        pending.addLast(copy)
                    }
                }
                retain(proxy)
                val group = SagerDatabase.groupDao.getById(proxy.groupId)
                groups[proxy.groupId] = group
                if (!forTest && !forExport && group?.isSelector == true) {
                    val rows = SagerDatabase.proxyDao.getByGroup(group.id)
                    rows.forEach(::retain); selectorIds = rows.map { it.id }
                }
                if (!forTest) {
                    rules = SagerDatabase.rulesDao.enabledRules().map { it.copy(packages = it.packages.toSet()) }
                    val rows = SagerDatabase.proxyDao.getEntities(rules.mapNotNull { r -> r.outbound.takeIf { it > 0 && it != proxy.id } }.toHashSet().toList())
                    rows.forEach(::retain); extraIds = rows.map { it.id }
                }
                // FIFO matches the old first-unvisited LinkedHashMap scan: initial
                // rows precede discoveries, and each retained ID is visited once.
                while (pending.isNotEmpty()) {
                    val row = pending.removeFirst()
                    if (row.groupId !in groups) groups[row.groupId] = SagerDatabase.groupDao.getById(row.groupId)
                    groups[row.groupId]?.let { g ->
                        for (id in listOf(g.frontProxy, g.landingProxy)) {
                            if (id !in entities) SagerDatabase.proxyDao.getById(id)?.let(::retain)
                        }
                    }
                    (row.requireBean() as? ChainBean)?.let { chain ->
                        SagerDatabase.proxyDao.getEntities(chain.proxies).forEach(::retain)
                    }
                }
            })
            // Package-manager state is read after releasing database locks.
            if (rules.any { it.packages.isNotEmpty() }) PackageCache.awaitLoadSync()
            val ruleInputs = rules.map { rule ->
                obj("id" to rule.id,"domains" to rule.domains,"ip" to rule.ip,"port" to rule.port,
                    "source_port" to rule.sourcePort,"network" to rule.network,"source" to rule.source,
                    "protocol" to rule.protocol,"outbound" to rule.outbound,
                    "uids" to rule.packages.mapNotNull { PackageCache[it]?.takeIf { uid -> uid >= 1000 } }.toSet().toList(),
                    "package_count" to rule.packages.size,"custom" to overlay(rule.config),
                    "rule_sets" to RouteRuleSet.decode(rule.ruleSets).map { it.snapshotJson { io.nekohasekai.sagernet.SagerNet.application.filesDir } },
                    "ip_is_private" to rule.ipIsPrivate,"source_ip_is_private" to rule.sourceIpIsPrivate)
            }
            val profileInputs = entities.values.map { row ->
                val bean = row.requireBean()
                val full = if (bean is ConfigBean && bean.type == 0) bean.config else null
                val profile = if (bean is ChainBean || (row.id == proxy.id && full != null)) null else
                    checkNotNull(RustOutboundConfig.captureProfileJson(bean)) { "Unsupported profile snapshot" }
                val muxBean = when (bean) { is VMessBean -> bean; is TrojanBean -> bean; else -> null }
                val mux = muxBean?.let { obj("enabled" to it.enableMux,"padding" to it.muxPadding,"concurrency" to it.muxConcurrency,"kind" to it.muxType) }
                val uot = runCatching { bean.javaClass.getField("sUoT").get(bean) == true }.getOrDefault(false)
                obj("id" to row.id,"group_id" to row.groupId,"name" to bean.displayName(),"server" to bean.serverAddress,
                    "outbound" to profile,"chain" to (bean as? ChainBean)?.proxies,"full_config" to full,
                    "custom_outbound" to overlay(bean.customOutboundJson),"custom_config" to overlay(bean.customConfigJson),
                    "mux" to mux,"uot" to uot)
            }
            // Freeze the Android/JVM map iteration order too. Treeified collision
            // buckets vary by runtime; the pure engine must not approximate them.
            val selectorOrder = HashMap<Long, Unit>().apply { selectorIds.forEach { put(it, Unit) } }.keys.toList()
            val request = obj("mode" to "snapshot", "version" to 1,"selected" to proxy.id,"for_test" to forTest,"for_export" to forExport,
                "settings" to settings,"profiles" to profileInputs,"groups" to groups.values.filterNotNull().map {
                    obj("id" to it.id,"selector" to it.isSelector,"front" to it.frontProxy,"landing" to it.landingProxy)
                },"rules" to ruleInputs,"selector_ids" to selectorIds,"selector_order" to selectorOrder,"extra_ids" to extraIds)
            return ConfigSnapshot(request.toString(), entities.toMap(), rules.associate { it.id to it.displayName() }, proxy.id)
        }
    }
}
