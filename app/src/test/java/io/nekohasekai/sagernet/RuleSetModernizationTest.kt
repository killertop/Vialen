package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptionsUtil
import moe.matsuri.nb4a.generateRuleSet
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class RuleSetModernizationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            val mockApp = mockk<SagerNet>(relaxed = true)
            every { mockApp.getDatabasePath(any()) } returns File("/tmp/test_mock_db")
            SagerNet.application = mockApp

            val mockKvDao = mockk<io.nekohasekai.sagernet.database.preference.KeyValuePair.Dao>(relaxed = true)
            every { mockKvDao.get(any()) } returns null
            mockkObject(io.nekohasekai.sagernet.database.preference.PublicDatabase.Companion)
            every { io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao } returns mockKvDao

            mockkObject(DataStore)
            val mockStore = mockk<RoomPreferenceDataStore>(relaxed = true)
            every { mockStore.getString(any(), any()) } returns ""
            every { DataStore.configurationStore } returns mockStore
            every { DataStore.profileCacheStore } returns mockStore

            mockkObject(SingBoxOptionsUtil)
            every { SingBoxOptionsUtil.domainStrategy(any()) } returns "prefer_ipv4"

            every { DataStore.globalAllowInsecure } returns false
            every { DataStore.serviceMode } returns Key.MODE_VPN
            every { DataStore.allowAccess } returns false
            every { DataStore.remoteDns } returns "tls://8.8.8.8"
            every { DataStore.directDns } returns "223.5.5.5"
            every { DataStore.enableDnsRouting } returns true
            every { DataStore.enableFakeDns } returns false
            every { DataStore.trafficSniffing } returns 1
            every { DataStore.ipv6Mode } returns IPv6Mode.ENABLE
            every { DataStore.enableClashAPI } returns false
            every { DataStore.logLevel } returns 2
            every { DataStore.mixedPort } returns 2080
            every { DataStore.mtu } returns 1500

            val mockGroupDao = mockk<ProxyGroup.Dao>(relaxed = true)
            every { mockGroupDao.getById(any()) } returns null

            val mockProxyDao = mockk<ProxyEntity.Dao>(relaxed = true)
            mockkObject(SagerDatabase.Companion)
            every { SagerDatabase.groupDao } returns mockGroupDao
            every { SagerDatabase.proxyDao } returns mockProxyDao
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            unmockkAll()
        }
    }

    private fun buildConfigWithRules(rules: List<RuleEntity>): Pair<SingBoxOptions.MyOptions, JsonObject> {
        val mockRuleDao = mockk<RuleEntity.Dao>(relaxed = true)
        every { mockRuleDao.enabledRules() } returns rules
        every { SagerDatabase.rulesDao } returns mockRuleDao

        val proxy = ProxyEntity().apply {
            id = 1
            type = 0 // SOCKS
            putBean(SOCKSBean().applyDefaultValues().apply {
                serverAddress = "127.0.0.1"
                serverPort = 1080
            })
        }
        val result = buildConfig(proxy)
        val options = gson.fromJson(result.config, SingBoxOptions.MyOptions::class.java)
        val json = JsonParser.parseString(result.config).asJsonObject
        return Pair(options, json)
    }

    private fun dnsRules(json: JsonObject): List<JsonObject> {
        return json.getAsJsonObject("dns").getAsJsonArray("rules").map { it.asJsonObject }
    }

    private fun logicalChildren(rule: JsonObject): List<JsonObject> {
        return rule.getAsJsonArray("rules").map { it.asJsonObject }
    }

    @Test
    fun testRuleSetDedup() {
        val rule1 = RuleEntity().apply {
            name = "Rule 1"
            domains = "geosite:cn\ngeosite:apple"
            ip = "geoip:cn"
            outbound = 0
        }
        val rule2 = RuleEntity().apply {
            name = "Rule 2"
            domains = "geosite:cn\ngeosite:category-ads-all"
            ip = "geoip:cn"
            outbound = 0
        }

        val (config, _) = buildConfigWithRules(listOf(rule1, rule2))
        val ruleSets = config.route.rule_set

        val tags = ruleSets.map { it.tag }
        assertEquals("Each tag must be unique (no duplicates)", tags.distinct().size, tags.size)
        assertTrue("Must contain geosite-cn", tags.contains("geosite-cn"))
        assertTrue("Must contain geosite-apple", tags.contains("geosite-apple"))
        assertTrue("Must contain geosite-category-ads-all", tags.contains("geosite-category-ads-all"))
        assertTrue("Must contain geoip-cn", tags.contains("geoip-cn"))
        assertEquals("Exact 4 deduplicated rule-sets expected", 4, ruleSets.size)
    }

    @Test
    fun testRuleSetDeterministicOrder() {
        val rule = RuleEntity().apply {
            name = "Mixed Rules"
            domains = "geosite:zhihu\ngeosite:bilibili\ngeosite:apple\ngeosite:cn"
            ip = "geoip:us\ngeoip:cn"
            outbound = 0
        }

        val (config, _) = buildConfigWithRules(listOf(rule))
        val ruleSets = config.route.rule_set
        val tags = ruleSets.map { it.tag }

        val sortedTags = tags.sorted()
        assertEquals("Rule-set declarations must be sorted alphabetically by tag", sortedTags, tags)
    }

    @Test
    fun testRuleSetTagCollisionSafety() {
        // 1. Direct generateRuleSet with an identical basename URL
        val ruleList = listOf(
            "geosite:cn",
            "https://custom-provider.example.com/geosite-cn.srs"
        )
        val ruleSets = mutableListOf<SingBoxOptions.RuleSet>()
        generateRuleSet(ruleList, ruleSets)

        assertEquals(2, ruleSets.size)
        val tag1 = ruleSets[0].tag
        val tag2 = ruleSets[1].tag
        org.junit.Assert.assertNotEquals("Tags must never collide", tag1, tag2)
        assertEquals("geosite-cn", tag1)
        assertEquals("user-geosite-cn.srs", tag2)

        // 2. Full ConfigBuilder integration proving both declarations and rule references survive
        val rule1 = RuleEntity().apply {
            name = "Official Geosite Rule"
            domains = "geosite:cn"
            outbound = 0
        }
        val rule2 = RuleEntity().apply {
            name = "User Custom SRS Rule"
            domains = "https://custom-provider.example.com/geosite-cn.srs"
            outbound = 0
        }

        val (config, json) = buildConfigWithRules(listOf(rule1, rule2))
        val declaredTags = config.route.rule_set.map { it.tag }

        assertTrue("Must retain official geosite-cn", declaredTags.contains("geosite-cn"))
        assertTrue("Must retain user-namespaced custom SRS tag", declaredTags.contains("user-geosite-cn.srs"))
        assertEquals("Both rule-sets must be preserved without collision loss", 2, declaredTags.size)

        val routeRules = json.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }
        val r1 = routeRules.firstOrNull { it.has("rule_set") && it.getAsJsonArray("rule_set").map { s -> s.asString }.contains("geosite-cn") }
        val r2 = routeRules.firstOrNull { it.has("rule_set") && it.getAsJsonArray("rule_set").map { s -> s.asString }.contains("user-geosite-cn.srs") }
        assertNotNull("Rule 1 must target official geosite-cn", r1)
        assertNotNull("Rule 2 must target user custom user-geosite-cn.srs", r2)
    }

    @Test
    fun testSourceGeoIPSemantics() {
        val rule = RuleEntity().apply {
            name = "Source Rule"
            source = "geoip:cn"
            outbound = 0
        }

        val (_, json) = buildConfigWithRules(listOf(rule))
        val routeRules = json.getAsJsonObject("route").getAsJsonArray("rules")

        val matchingRule = routeRules.map { it.asJsonObject }.firstOrNull { r ->
            r.has("rule_set") && r.getAsJsonArray("rule_set").map { it.asString }.contains("geoip-cn")
        }
        assertNotNull("Must generate route rule with geoip-cn rule-set", matchingRule)
        assertEquals(
            "rule_set_ip_cidr_match_source must be true for source GeoIP",
            true,
            matchingRule?.get("rule_set_ip_cidr_match_source")?.asBoolean
        )
    }

    @Test
    fun testDestinationGeoIPSemantics() {
        val rule = RuleEntity().apply {
            name = "Dest Rule"
            ip = "geoip:cn"
            outbound = 0
        }

        val (_, json) = buildConfigWithRules(listOf(rule))
        val routeRules = json.getAsJsonObject("route").getAsJsonArray("rules")

        val matchingRule = routeRules.map { it.asJsonObject }.firstOrNull { r ->
            r.has("rule_set") && r.getAsJsonArray("rule_set").map { it.asString }.contains("geoip-cn")
        }
        assertNotNull("Must generate route rule with geoip-cn rule-set", matchingRule)
        assertNull(
            "rule_set_ip_cidr_match_source must NOT be set for destination GeoIP",
            matchingRule?.get("rule_set_ip_cidr_match_source")
        )
    }

    @Test
    fun testPrivateIPSemantics() {
        val rule = RuleEntity().apply {
            name = "Private IP Rule"
            ip = "geoip:private"
            source = "geoip:private"
            outbound = 0
        }

        val (config, json) = buildConfigWithRules(listOf(rule))
        val routeRules = json.getAsJsonObject("route").getAsJsonArray("rules")

        val matchingRule = routeRules.map { it.asJsonObject }.firstOrNull { r ->
            r.has("ip_is_private") && r.get("ip_is_private").asBoolean
        }
        assertNotNull("Must generate ip_is_private rule", matchingRule)
        assertEquals(true, matchingRule?.get("ip_is_private")?.asBoolean)
        assertEquals(true, matchingRule?.get("source_ip_is_private")?.asBoolean)

        // Ensure no pseudo rule-set "geoip-private" is declared
        val ruleSets = config.route.rule_set ?: emptyList()
        assertFalse("geoip:private must NOT produce a rule-set declaration", ruleSets.any { it.tag.contains("private") })
    }

    @Test
    fun testDnsResponseGeoIpGeneration() {
        val rule = RuleEntity().apply {
            name = "DNS response GeoIP"
            domains = "domain:example.com"
            ip = "geoip:cn"
            outbound = -1L
        }

        val (config, json) = buildConfigWithRules(listOf(rule))
        val rules = dnsRules(json)
        val evaluateIndex = rules.indexOfFirst {
            it.get("action")?.asString == "evaluate" &&
                it.get("server")?.asString == "dns-remote" &&
                it.getAsJsonArray("domain_suffix")?.map { value -> value.asString }?.contains("example.com") == true
        }
        assertTrue("GeoIP response rule must be preceded by a dns-remote evaluate", evaluateIndex >= 0)

        val responseRule = rules.drop(evaluateIndex + 1).first {
            it.get("type")?.asString == "logical"
        }
        assertEquals("and", responseRule.get("mode").asString)
        assertEquals("route", responseRule.get("action").asString)
        assertEquals("dns-direct", responseRule.get("server").asString)

        val children = logicalChildren(responseRule)
        val queryChild = children.first { it.has("domain_suffix") }
        val responseChild = children.first { it.get("match_response")?.asBoolean == true }
        assertEquals(listOf("example.com"), queryChild.getAsJsonArray("domain_suffix").map { it.asString })
        assertEquals(listOf("geoip-cn"), responseChild.getAsJsonArray("rule_set").map { it.asString })
        assertFalse("Response child must not absorb the query predicate", responseChild.has("domain_suffix"))

        assertEquals(1, config.route.rule_set.count { it.tag == "geoip-cn" })
        val jsonStr = json.toString()
        assertFalse(jsonStr.contains("\"geoip\""))
        assertFalse(jsonStr.contains("source_geoip"))
        assertFalse(jsonStr.contains("rule_set_ip_cidr_accept_empty"))
        assertFalse(jsonStr.contains("download_detour"))
    }

    @Test
    fun testDnsResponsePrivateGeneration() {
        val rule = RuleEntity().apply {
            name = "DNS response private"
            ip = "geoip:private"
            outbound = 0L
        }

        val (config, json) = buildConfigWithRules(listOf(rule))
        val rules = dnsRules(json)
        val evaluateRule = rules.first { it.get("action")?.asString == "evaluate" }
        assertEquals("dns-remote", evaluateRule.get("server").asString)

        val responseRule = rules.first { it.get("match_response")?.asBoolean == true }
        assertEquals(true, responseRule.get("ip_is_private").asBoolean)
        assertEquals("route", responseRule.get("action").asString)
        assertEquals("dns-remote", responseRule.get("server").asString)
        assertFalse(responseRule.has("rule_set"))
        assertFalse(config.route.rule_set.any { it.tag == "geoip-private" })
    }

    @Test
    fun testDnsResponseCidrGeneration() {
        val rule = RuleEntity().apply {
            name = "DNS response CIDR"
            ip = "1.2.3.0/24"
            outbound = -1L
        }

        val (_, json) = buildConfigWithRules(listOf(rule))
        val rules = dnsRules(json)
        val evaluateRule = rules.first { it.get("action")?.asString == "evaluate" }
        assertEquals("dns-remote", evaluateRule.get("server").asString)

        val responseRule = rules.first { it.get("match_response")?.asBoolean == true }
        assertEquals(listOf("1.2.3.0/24"), responseRule.getAsJsonArray("ip_cidr").map { it.asString })
        assertEquals("route", responseRule.get("action").asString)
        assertEquals("dns-direct", responseRule.get("server").asString)
    }

    @Test
    fun testDnsDomainAndResponseIpUsesLogicalAnd() {
        val rule = RuleEntity().apply {
            name = "DNS domain AND response IP"
            domains = "geosite:cn"
            ip = "geoip:cn"
            outbound = -1L
        }

        val (_, json) = buildConfigWithRules(listOf(rule))
        val logicalRule = dnsRules(json).first { it.get("type")?.asString == "logical" }
        assertEquals("and", logicalRule.get("mode").asString)

        val children = logicalChildren(logicalRule)
        val queryChild = children.first { it.get("match_response") == null }
        val responseChild = children.first { it.get("match_response")?.asBoolean == true }
        assertEquals(listOf("geosite-cn"), queryChild.getAsJsonArray("rule_set").map { it.asString })
        assertEquals(listOf("geoip-cn"), responseChild.getAsJsonArray("rule_set").map { it.asString })
        assertFalse("Query and response rule-sets must not be OR-merged", queryChild.getAsJsonArray("rule_set").map { it.asString }.contains("geoip-cn"))
        assertFalse("Query and response rule-sets must not be OR-merged", responseChild.getAsJsonArray("rule_set").map { it.asString }.contains("geosite-cn"))
    }

    @Test
    fun testDnsResponseRuleOrdering() {
        val first = RuleEntity().apply {
            name = "First DNS response rule"
            domains = "domain:first.example"
            ip = "10.0.0.0/8"
            outbound = -1L
        }
        val second = RuleEntity().apply {
            name = "Second DNS response rule"
            domains = "domain:second.example"
            ip = "geoip:private"
            outbound = -2L
        }

        val (_, json) = buildConfigWithRules(listOf(first, second))
        val generated = dnsRules(json).filter {
            it.get("action")?.asString == "evaluate" || it.get("type")?.asString == "logical"
        }
        assertEquals(4, generated.size)
        assertEquals("evaluate", generated[0].get("action").asString)
        assertEquals(listOf("first.example"), generated[0].getAsJsonArray("domain_suffix").map { it.asString })
        assertEquals("logical", generated[1].get("type").asString)
        assertEquals("route", generated[1].get("action").asString)
        assertEquals("evaluate", generated[2].get("action").asString)
        assertEquals(listOf("second.example"), generated[2].getAsJsonArray("domain_suffix").map { it.asString })
        assertEquals("logical", generated[3].get("type").asString)
        assertEquals("reject", generated[3].get("action").asString)
    }

    @Test
    fun testSharedHttpClientAndNoDownloadDetour() {
        val rule = RuleEntity().apply {
            name = "Remote Rule"
            domains = "geosite:cn"
            ip = "geoip:cn"
            outbound = 0
        }

        val (config, _) = buildConfigWithRules(listOf(rule))
        val jsonStr = gson.toJson(config)

        assertFalse("Generated config must NOT contain deprecated download_detour", jsonStr.contains("download_detour"))
        assertEquals("default-http-client", config.route.default_http_client)
        for (rs in config.route.rule_set) {
            assertEquals("default-http-client", rs.http_client)
            assertEquals("binary", rs.format)
            assertEquals("remote", rs.type)
        }
    }
}
