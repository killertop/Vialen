package io.nekohasekai.sagernet.oracle

import io.nekohasekai.sagernet.fmt.*

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildLegacyConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val selectorNames = ArrayList<String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)
    // The migrated generators receive one build-level policy snapshot.
    val outboundGlobalAllowInsecure = DataStore.globalAllowInsecure

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        if (!forTest && DataStore.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                external_ui = "../files/yacd"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("server"))
        }

        inbounds = mutableListOf()
        endpoints = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                val addresses = mutableListOf<String>()
                if (ipv6Mode != IPv6Mode.ONLY) {
                    addresses.add(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                }
                if (ipv6Mode != IPv6Mode.DISABLE) {
                    addresses.add(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                }
                address = addresses
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            var pastEntity: ProxyEntity? = null
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // selector human readable name
                if (buildSelector && index == 0) {
                    tagOut = selectorName(bean.displayName())
                }

                // chain rules
                if (index > 0) {
                    pastOutbound._hack_config_map["detour"] = tagOut
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // now tagOut is determined
                if (needGlobal) {
                    globalOutbounds[proxyEntity.id]?.let {
                        if (index == 0) chainTagOut = it // single, duplicate chain
                        return@forEachIndexed
                    }
                    globalOutbounds[proxyEntity.id] = tagOut
                }

                currentOutbound = when (bean) {
                    is ConfigBean -> CustomSingBoxOption(bean.config)

                    is ShadowTLSBean -> // before StandardV2RayBean
                        buildSingBoxOutboundShadowTLSBean(bean)

                    is StandardV2RayBean -> // http/trojan/vmess/vless
                        buildSingBoxOutboundStandardV2RayBean(bean)

                    is HysteriaBean ->
                        buildSingBoxOutboundHysteriaBean(bean)

                    is TuicBean ->
                        buildSingBoxOutboundTuicBean(bean, outboundGlobalAllowInsecure)

                    is SOCKSBean ->
                        buildSingBoxOutboundSocksBean(bean)

                    is ShadowsocksBean ->
                        buildSingBoxOutboundShadowsocksBean(bean)

                    is WireGuardBean ->
                        buildSingBoxOutboundWireguardBean(bean)

                    is AnyTLSBean ->
                        buildSingBoxOutboundAnyTLSBean(bean)

                    else -> throw IllegalStateException("can't reach")
                }

                // internal mux
                if (!muxApplied) {
                    val muxObj = proxyEntity.singMux()
                    if (muxObj != null && muxObj.enabled) {
                        muxApplied = true
                        currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["domain_strategy"] =
                        if (forTest) "" else defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort

                if (currentOutbound is Endpoint_WireGuardOptions) {
                    endpoints.add(currentOutbound)
                } else {
                    outbounds.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            if (rule.packages.isNotEmpty()) {
                PackageCache.awaitLoadSync()
            }
            val uidList = rule.packages.map {
                if (!isVPN) {
                    Toast.makeText(
                        SagerNet.application,
                        SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                PackageCache[it]?.takeIf { uid -> uid >= 1000 }
            }.toHashSet().filterNotNull()
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                if (uidList.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                    user_id = uidList
                }
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                    generateRuleSet(domainList, ruleSets)
                }
                if (rule.ip.isNotBlank()) {
                    val ipList = rule.ip.listByLineOrComma()
                    makeSingBoxRule(ipList, true)
                    generateRuleSet(ipList, ruleSets)
                }

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    val srcList = rule.source.listByLineOrComma()
                    val srcCidrs = mutableListOf<String>()
                    val srcRuleSets = mutableListOf<String>()
                    for (item in srcList) {
                        if (item == "geoip:private" || item == "geoip-private") {
                            source_ip_is_private = true
                        } else if (item.startsWith("geoip:") || item.startsWith("geoip-")) {
                            val tag = normalizeRuleSetTag(item)
                            srcRuleSets.add(tag)
                            generateRuleSet(listOf(tag), ruleSets)
                        } else {
                            srcCidrs.add(item)
                        }
                    }
                    if (srcCidrs.isNotEmpty()) source_ip_cidr = srcCidrs
                    if (srcRuleSets.isNotEmpty()) {
                        if (rule_set == null) rule_set = mutableListOf()
                        rule_set.addAll(srcRuleSets)
                        rule_set_ip_cidr_match_source = true
                    }
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (uidList.isNotEmpty()) user_id = uidList
                        domainList?.let {
                            makeSingBoxRule(it)
                            generateRuleSet(it, ruleSets)
                        }
                    }
                }

                fun makeDnsResponseRuleObj(ipList: List<String>): DNSRule_DefaultOptions {
                    val responseIPRule = Rule_DefaultOptions().apply {
                        makeSingBoxRule(ipList, true)
                    }
                    generateRuleSet(ipList, ruleSets)
                    return DNSRule_DefaultOptions().apply {
                        match_response = true
                        ip_is_private = responseIPRule.ip_is_private
                        ip_cidr = responseIPRule.ip_cidr
                        rule_set = responseIPRule.rule_set
                    }
                }

                fun addDnsResponseRule(targetServer: String?, targetAction: String) {
                    val ipList = rule.ip.listByLineOrComma()
                    val evaluateRule = makeDnsRuleObj().apply {
                        action = "evaluate"
                        server = "dns-remote"
                    }
                    userDNSRuleList += evaluateRule

                    val responseRule = makeDnsResponseRuleObj(ipList)
                    val queryRule = makeDnsRuleObj()
                    if (queryRule.checkEmpty()) {
                        userDNSRuleList += responseRule.apply {
                            action = targetAction
                            server = targetServer
                        }
                    } else {
                        userDNSRuleList += DNSRule_LogicalOptions().apply {
                            type = "logical"
                            mode = "and"
                            rules = listOf(queryRule, responseRule)
                            action = targetAction
                            server = targetServer
                        }
                    }
                }

                when (rule.outbound) {
                    -1L -> {
                        if (rule.ip.isNotBlank()) {
                            addDnsResponseRule("dns-direct", "route")
                        } else {
                            userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                        }
                    }

                    0L -> {
                        if (rule.ip.isNotBlank()) {
                            addDnsResponseRule("dns-remote", "route")
                        } else {
                            if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-fake"
                                inbound = listOf("tun-in")
                            }
                            userDNSRuleList += makeDnsRuleObj().apply {
                                server = "dns-remote"
                            }
                        }
                    }

                    -2L -> {
                        if (rule.ip.isNotBlank()) {
                            addDnsResponseRule(null, "reject")
                        } else {
                            userDNSRuleList += makeDnsRuleObj().apply {
                                action = "reject"
                            }
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                if (ruleObj.outbound.isNullOrBlank()) {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route.rules.add(ruleObj)
                    route.rule_set.addAll(ruleSets)
                }
            }
        }

        // 对 rule_set tag 去重并按 tag 稳定排序
        if (route.rule_set != null) {
            route.rule_set = route.rule_set
                .distinctBy { it.tag }
                .sortedBy { it.tag }
                .toMutableList()
            if (route.rule_set.any { it.type == "remote" }) {
                this.http_clients = mutableListOf(
                    SingBoxOptions.HTTPClient().apply {
                        tag = "default-http-client"
                        detour = TAG_DIRECT
                    }
                )
                route.default_http_client = "default-http-client"
            }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(
            SingBoxOptionsUtil.parseTypedDnsServer("local", "dns-local", detour = TAG_DIRECT)
        )

        directDNS.firstOrNull().let {
            val s = it ?: throw Exception("No direct DNS, check your settings!")
            dns.servers.add(
                SingBoxOptionsUtil.parseTypedDnsServer(
                    s,
                    "dns-direct",
                    domainResolver = "dns-local",
                    detour = TAG_DIRECT
                )
            )
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) {
                val s = it ?: throw Exception("No remote DNS, check your settings!")
                dns.servers.add(
                    SingBoxOptionsUtil.parseTypedDnsServer(
                        s,
                        "dns-remote",
                        domainResolver = "dns-direct"
                    )
                )
            }
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                when (it) {
                    is DNSRule_DefaultOptions -> {
                        if (!it.checkEmpty() || it.action == "evaluate") dns.rules.add(it)
                    }

                    is DNSRule_LogicalOptions -> dns.rules.add(it)
                }
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            if (needSniff) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "sniff"
                    if (needSniffOverride) {
                        override_destination = true
                    }
                })
            }
            val domainStrategy = genDomainStrategy(DataStore.resolveDestination)
            if (domainStrategy.isNotEmpty()) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    action = "resolve"
                    strategy = domainStrategy
                })
            }
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.servers.add(
                    SingBoxOptionsUtil.parseTypedDnsServer("fakeip", "dns-fake")
                )
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L
        )
    }

}
