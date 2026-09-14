package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.core.CoreClient
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.utils.PackageCache
import java.net.URI

/** Android IO is confined to capture; compilation receives an immutable domain request. */
internal class ConfigSnapshot private constructor(
    private val request: JsonObject?,
    private val entities: Map<Long, ProxyEntity>,
    val ruleNames: Map<Long, String>,
    private val selected: Long,
    private val selectorGroup: Long,
    private val rawConfig: String? = null,
) {
    val tunMtu: Int? get() = request?.getAsJsonObject("platform")?.get("mtu")?.asInt
    data class Output(val result: ConfigBuildResult, val warnings: List<Pair<Long, String>>)

    fun generate(): Output {
        rawConfig?.let {
            require(JsonParser.parseString(it).isJsonObject) { "Configuration must be an object" }
            return Output(ConfigBuildResult(it, emptyList(), selected, emptyMap(), emptyMap(), -1), emptyList())
        }
        return decodePlan(CoreClient.compile(requireNotNull(request)))
    }

    internal fun decodePlan(plan: JsonObject): Output {
        val metadata = plan.getAsJsonObject("metadata")
        val traffic = linkedMapOf<String, List<ProxyEntity>>()
        val tags = linkedMapOf<Long, String>()
        val bindings = metadata.getAsJsonArray("bindings").map { it.asJsonObject }
        // Each reference owns one counter at its first hop. Attribute that counter
        // to its participating nodes and chain row, without counting every hop twice.
        bindings.groupBy { it["reference_id"].asString }.forEach { (reference, hops) ->
            val first = hops.single { it["hop"].asInt == 0 }
            val ids = hops.map { it["profile_id"].asString.removePrefix("node:").toLong() }.toMutableList()
            ids += reference.toLong()
            traffic[first["tag"].asString] = ids.distinct().map { id -> checkNotNull(entities[id]) { "Unknown compiled profile $id" }.copy() }
        }
        metadata.getAsJsonArray("selector_candidates").forEach {
            val candidate = it.asJsonObject
            val id = candidate["reference_id"].asString.toLong()
            check(entities.containsKey(id)) { "Unknown selector candidate" }
            tags[id] = candidate["tag"].asString
        }
        val warnings = metadata.getAsJsonArray("diagnostics")?.map {
            val d = it.asJsonObject
            val id = d["rule_id"]?.asString?.substringBefore(':')?.toLongOrNull() ?: 0L
            id to when (val code = d["code"].asString) { "dns_projection_skipped" -> "DNS_RULE_NOT_PROJECTED"; else -> code }
        }.orEmpty().distinct()
        return Output(ConfigBuildResult(plan["config"].asString, emptyList(), selected, traffic, tags, selectorGroup), warnings)
    }

    override fun toString() = "ConfigSnapshot(domain)"

    companion object {
        private val gson = Gson()
        internal fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
            fields.forEach { (key, value) -> if (value != null) add(key, if (value is JsonElement) value else gson.toJsonTree(value)) }
        }
        private fun parts(value: String) = value.split(',', '\n').map(String::trim).filter(String::isNotEmpty)

        internal fun sniff(enabled: Int): Boolean = when (enabled) {
            0 -> false
            1 -> true
            2 -> error("Destination override sniffing is not supported; use routing sniffing")
            else -> error("Invalid sniffing mode")
        }

        internal fun dns(value: String): JsonObject {
            val text = value.trim()
            require(text.isNotEmpty()) { "DNS server is required" }
            if (text == "local") return obj("type" to "local")
            require(!text.contains('\n') && !text.contains(';')) { "Configure one DNS server per field" }
            val uri = URI(if ("://" in text) text else "udp://$text")
            require(uri.userInfo == null && uri.fragment == null && uri.query == null && !uri.host.isNullOrEmpty()) { "Invalid DNS server" }
            val type = uri.scheme
            require(type in setOf("udp", "tcp", "tls", "quic", "https", "h3")) { "Unsupported DNS transport" }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid DNS port" }
            require(type in setOf("https", "h3") || uri.path.isNullOrEmpty()) { "This DNS transport has no path" }
            return obj("type" to type, "server" to uri.host.removeSurrounding("[", "]"),
                "port" to uri.port.takeIf { it != -1 }, "path" to uri.rawPath?.takeIf { it.isNotEmpty() })
        }

        /** Form strings become explicit categories; the core owns validation and matching. */
        internal fun match(rule: RuleEntity, uids: List<Int>): JsonObject {
            require(rule.config.isBlank()) { "Rule ${rule.id}: custom JSON rules are not supported" }
            val domains = linkedMapOf<String, MutableList<String>>()
            for (entry in rule.domains.lines().flatMap { line -> if (line.trim().startsWith("regexp:")) listOf(line.trim()) else parts(line) }.filter(String::isNotEmpty)) {
                val key: String
                val value: String
                when {
                    entry.startsWith("full:") -> { key = "domains"; value = entry.substringAfter(':') }
                    entry.startsWith("domain:") -> { key = "domain_suffixes"; value = entry.substringAfter(':') }
                    entry.startsWith("keyword:") -> { key = "domain_keywords"; value = entry.substringAfter(':') }
                    entry.startsWith("regexp:") -> { key = "domain_regexes"; value = entry.substringAfter(':') }
                    ':' in entry -> error("Rule ${rule.id}: unsupported domain condition")
                    else -> { key = "domain_suffixes"; value = entry }
                }
                require(value.isNotBlank()) { "Empty domain condition" }
                domains.getOrPut(key) { mutableListOf() }.add(value)
            }
            val result = obj("ip_cidrs" to parts(rule.ip), "source_ip_cidrs" to parts(rule.source),
                "ip_is_private" to rule.ipIsPrivate, "source_ip_is_private" to rule.sourceIpIsPrivate,
                "networks" to parts(rule.network), "protocols" to parts(rule.protocol), "uids" to uids)
            domains.forEach { (key, values) -> result.add(key, gson.toJsonTree(values)) }
            fun ports(raw: String, singles: String, ranges: String) {
                val tokens = parts(raw)
                result.add(singles, gson.toJsonTree(tokens.filter { ':' !in it }.map { it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: error("Invalid port") }))
                result.add(ranges, gson.toJsonTree(tokens.filter { ':' in it }))
            }
            ports(rule.port, "ports", "port_ranges")
            ports(rule.sourcePort, "source_ports", "source_port_ranges")
            return result
        }

        internal fun assemble(
            selected: Long, rows: Map<Long, ProxyEntity>, groups: Map<Long, ProxyGroup?>,
            selectorIds: List<Long>, rules: List<RuleEntity>, policy: JsonObject, platform: JsonObject,
            purpose: String, insecure: Boolean = false,
            uids: (Set<String>) -> List<Int> = { emptyList() },
            ruleSetSnapshot: (RouteRuleSet) -> JsonObject = { it.json() },
        ): JsonObject {
            fun raw(id: Long): String { require(rows.containsKey(id)) { "Missing profile reference $id" }; return "node:$id" }
            val profiles = mutableListOf<Any>()
            val rawOutbounds = mutableListOf<JsonObject>()
            val chains = mutableListOf<JsonObject>()
            rows.values.forEach { row ->
                val document = ProfileDocument.decode(row.document)
                when (document.kind) {
                    "node" -> {
                        var profile = row.requireProfile().copy(id = raw(row.id))
                        val tls = profile.tls
                        if (insecure && tls?.enabled == true && tls.reality == null) {
                            profile = profile.copy(tls = tls.copy(insecure = true))
                        }
                        profiles += profile
                    }
                    // The form lists network-facing proxy first, then the destination.
                    "chain" -> chains += obj("id" to raw(row.id), "hops" to document.hops.asReversed().map(::raw))
                    "raw_config" -> {
                        require(document.scope == "outbound") { "Full configuration cannot be a chain hop" }
                        val rawJson = JsonParser.parseString(document.content)
                        require(rawJson.isJsonObject) { "Raw outbound must be an object" }
                        rawOutbounds += obj("id" to raw(row.id), "json" to rawJson)
                    }
                    else -> error("Unsupported document kind")
                }
                val group = groups[row.groupId]
                val hops = mutableListOf<String>()
                group?.landingProxy?.takeIf { it > 0 }?.let { hops += raw(it) }
                hops += raw(row.id)
                group?.frontProxy?.takeIf { it > 0 }?.let { hops += raw(it) }
                chains += obj("id" to row.id.toString(), "hops" to hops)
            }
            val sets = linkedMapOf<Pair<String, String>, JsonObject>()
            val inputs = rules.flatMap { rule ->
                val userIds = uids(rule.packages)
                require(rule.packages.isEmpty() || userIds.size == rule.packages.size) { "Rule ${rule.id}: an application is missing or has no usable UID" }
                val base = match(rule, userIds.distinct())
                val refs = RouteRuleSet.decode(rule.ruleSets)
                // Rule-set alternatives are ORed, while other categories remain ANDed.
                // Separate adjacent rules retain this OR with an identical terminal action.
                val directions = refs.groupBy { it.match == "source" }.ifEmpty { mapOf(false to emptyList()) }
                directions.entries.mapIndexed { index, (source, entries) ->
                    val condition = base.deepCopy()
                    val ids = entries.map { ref ->
                        val snap = ruleSetSnapshot(ref)
                        val location = snap["source"].asString
                        val key = location to ref.format
                        val set = sets.getOrPut(key) {
                            obj("id" to "set-${sets.size}", "type" to if (location.startsWith("https://")) "remote" else "local",
                                "format" to ref.format, "url" to location.takeIf { it.startsWith("https://") },
                                "path" to location.takeUnless { it.startsWith("https://") }, "initial_path" to snap["initial_path"]?.takeIf { location.startsWith("https://") },
                                "download_detour" to "direct".takeIf { location.startsWith("https://") })
                        }
                        set["id"].asString
                    }.distinct()
                    condition.add("rule_set_ids", gson.toJsonTree(ids))
                    condition.addProperty("rule_set_ip_cidr_match_source", source)
                    val target = when (rule.outbound) {
                        0L -> obj("kind" to "selected")
                        -1L -> obj("kind" to "direct")
                        -2L -> null
                        else -> { require(rows.containsKey(rule.outbound)) { "Rule ${rule.id}: missing outbound" }; obj("kind" to "reference", "id" to rule.outbound.toString()) }
                    }
                    obj("id" to "${rule.id}:$index", "match" to condition, "action" to if (target == null) "reject" else "route", "target" to target)
                }
            }
            val finalPolicy = policy.deepCopy().apply { add("rules", gson.toJsonTree(inputs)) }
            return obj("profiles" to profiles, "raw_outbounds" to rawOutbounds, "chains" to chains, "selected_id" to selected.toString(),
                "selector_ids" to selectorIds.map(Long::toString), "rule_sets" to sets.values.toList(),
                "policy" to finalPolicy, "platform" to platform, "purpose" to purpose)
        }

        fun capture(proxy: ProxyEntity, forTest: Boolean, forExport: Boolean): ConfigSnapshot {
            val chosen = ProfileDocument.decode(proxy.document)
            if (chosen.kind == "raw_config" && chosen.scope == "config") {
                if (forTest) throw io.nekohasekai.sagernet.bg.proto.UnsupportedStandaloneProbe()
                return ConfigSnapshot(null, mapOf(proxy.id to proxy.copy()), emptyMap(), proxy.id, -1, chosen.content)
            }
            lateinit var policy: JsonObject
            lateinit var platform: JsonObject
            var insecure = false
            PublicDatabase.instance.runInTransaction(Runnable {
                require(DataStore.globalCustomConfig.isBlank()) { "Global custom JSON overlays are not supported" }
                fun strategy(key: String) = DataStore.configurationStore.getString(key).orEmpty().ifEmpty { "auto" }
                val directDns = dns(DataStore.directDns).apply { addProperty("strategy", strategy("domain_strategy_for_direct")) }
                val remoteDns = dns(DataStore.remoteDns).apply { addProperty("strategy", strategy("domain_strategy_for_remote")) }
                val ipv6 = when (DataStore.ipv6Mode) {
                    IPv6Mode.DISABLE -> "ipv4_only"; IPv6Mode.ENABLE -> "prefer_ipv4"
                    IPv6Mode.PREFER -> "prefer_ipv6"; IPv6Mode.ONLY -> "ipv6_only"
                    else -> error("Invalid IPv6 policy")
                }
                val vpn = DataStore.serviceMode == Key.MODE_VPN && !forTest
                policy = obj("dns" to obj("direct" to directDns, "remote" to remoteDns,
                    "route_domains" to DataStore.enableDnsRouting, "fake_ip" to (DataStore.enableFakeDns && vpn)),
                    "ipv6" to ipv6, "server_strategy" to strategy("domain_strategy_for_server"), "sniff" to sniff(DataStore.trafficSniffing), "resolve_destination" to DataStore.resolveDestination,
                    "bypass_lan" to DataStore.bypassLanInCore)
                val addresses = mutableListOf("${VpnService.PRIVATE_VLAN4_CLIENT}/30")
                if (DataStore.ipv6Mode != IPv6Mode.DISABLE) addresses += "${VpnService.PRIVATE_VLAN6_CLIENT}/126"
                platform = obj("vpn" to vpn, "tun_addresses" to addresses, "mtu" to
                    (if (vpn) io.nekohasekai.sagernet.utils.TunMtu.requireValid(DataStore.mtu)
                    else io.nekohasekai.sagernet.utils.TunMtu.DEFAULT),
                    "stack" to when (DataStore.tunImplementation) { TunImplementation.GVISOR -> "gvisor"; TunImplementation.SYSTEM -> "system"; TunImplementation.MIXED -> "mixed"; else -> error("Invalid TUN stack") },
                    "mixed_port" to if (forTest) 0 else DataStore.mixedPort, "allow_lan" to DataStore.allowAccess, "supports_uid_rules" to vpn)
                insecure = DataStore.globalAllowInsecure
            })
            val entities = linkedMapOf<Long, ProxyEntity>()
            val groups = linkedMapOf<Long, ProxyGroup?>()
            val pending = java.util.ArrayDeque<ProxyEntity>()
            var rules = emptyList<RuleEntity>()
            var candidates = listOf(proxy.id)
            var selectorGroup = -1L
            SagerDatabase.instance.runInTransaction(Runnable {
                fun retain(row: ProxyEntity) { if (row.id !in entities) { val copy = row.copy(); entities[row.id] = copy; pending.add(copy) } }
                fun load(id: Long) { if (id !in entities) retain(requireNotNull(SagerDatabase.proxyDao.getById(id)) { "Missing profile reference $id" }) }
                retain(proxy)
                val group = SagerDatabase.groupDao.getById(proxy.groupId)
                if (!forTest && !forExport && group?.isSelector == true) {
                    val rows = SagerDatabase.proxyDao.getByGroup(group.id)
                    rows.forEach(::retain)
                    candidates = rows.map { it.id }
                    selectorGroup = group.id
                }
                if (!forTest) {
                    rules = SagerDatabase.rulesDao.enabledRules().map { it.copy(packages = it.packages.toSet()) }
                    rules.map { it.outbound }.filter { it > 0 }.forEach(::load)
                }
                while (pending.isNotEmpty()) {
                    val row = pending.removeFirst()
                    val g = groups.getOrPut(row.groupId) { SagerDatabase.groupDao.getById(row.groupId) }
                    listOfNotNull(g?.frontProxy, g?.landingProxy).filter { it > 0 }.forEach(::load)
                    ProfileDocument.decode(row.document).takeIf { it.kind == "chain" }?.hops?.forEach(::load)
                }
            })
            if (rules.any { it.packages.isNotEmpty() }) PackageCache.awaitLoadSync()
            val request = assemble(proxy.id, entities, groups, candidates, rules, policy, platform,
                if (forTest) "probe" else if (forExport) "export" else "run", insecure,
                uids = { packages -> packages.map { requireNotNull(PackageCache[it]) { "Application $it is not installed" }.also { uid -> require(uid >= 0) { "Invalid application UID" } } } },
                ruleSetSnapshot = { it.snapshotJson { SagerNet.application.filesDir } })
            return ConfigSnapshot(request, entities, rules.associate { it.id to it.displayName() }, proxy.id, selectorGroup)
        }
    }
}
