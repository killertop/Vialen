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
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class RuleSetModernizationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            io.mockk.mockkStatic(android.widget.Toast::class)
            every { android.widget.Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)
            every { android.widget.Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
            val mockApp = mockk<SagerNet>(relaxed = true)
            every { mockApp.getDatabasePath(any()) } returns File("/tmp/test_mock_db")
            every { mockApp.filesDir } returns File(System.getProperty("java.io.tmpdir"), "vialen-rules-no-downloads")
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


            every { DataStore.globalCustomConfig } returns ""
            every { DataStore.resolveDestination } returns false
            every { DataStore.bypassLanInCore } returns false
            every { DataStore.tunImplementation } returns TunImplementation.SYSTEM
            every { DataStore.globalAllowInsecure } returns false
            every { DataStore.serviceMode } returns Key.MODE_VPN
            every { DataStore.allowAccess } returns false
            every { DataStore.remoteDns } returns "tls://8.8.8.8"
            every { DataStore.directDns } returns "223.5.5.5"
            every { DataStore.enableDnsRouting } returns true
            every { DataStore.enableFakeDns } returns false
            every { DataStore.trafficSniffing } returns 1
            every { DataStore.ipv6Mode } returns IPv6Mode.ENABLE
            every { DataStore.logLevel } returns 2
            every { DataStore.mixedPort } returns 2080
            every { DataStore.mtu } returns 1500

            val mockGroupDao = mockk<ProxyGroup.Dao>(relaxed = true)
            every { mockGroupDao.getById(any()) } returns null

            val mockProxyDao = mockk<ProxyEntity.Dao>(relaxed = true)
            mockkObject(SagerDatabase.Companion)
            io.nekohasekai.sagernet.installConfigSnapshotTransactions()
            every { SagerDatabase.groupDao } returns mockGroupDao
            every { SagerDatabase.proxyDao } returns mockProxyDao
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            unmockkAll()
        }
    }

    private fun buildConfigWithRules(rules: List<RuleEntity>): JsonObject {
        val mockRuleDao = mockk<RuleEntity.Dao>(relaxed = true)
        every { mockRuleDao.enabledRules() } returns rules.filter { it.enabled }.sortedBy { it.userOrder }
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
        val json = JsonParser.parseString(result.config).asJsonObject
        return json
    }

    private fun dnsRules(json: JsonObject): List<JsonObject> {
        return json.getAsJsonObject("dns").getAsJsonArray("rules")?.map { it.asJsonObject }.orEmpty()
    }

    private fun nativeRule() = RuleEntity(id = 100, enabled = true, outbound = -1)
    private fun ref(name: String, match: String = "destination") =
        io.nekohasekai.sagernet.database.RouteRuleSet(name, "https://example.net/" + name + ".srs", match = match)
    private fun sets(rule: RuleEntity, vararg refs: io.nekohasekai.sagernet.database.RouteRuleSet) = rule.apply {
        ruleSets = io.nekohasekai.sagernet.database.RouteRuleSet.encode(refs.toList())
    }
    private fun routes(json: JsonObject) = json.getAsJsonObject("route").getAsJsonArray("rules")?.map { it.asJsonObject }.orEmpty()

    @Test fun defaultPresetsCompileInPriorityOrderWithoutImplicitIpBypass() {
        val defaults = io.nekohasekai.sagernet.database.DefaultRouteRules.create { it.toString() }
            .onEachIndexed { index, rule -> rule.id = index + 1L }
        assertEquals(listOf(true, true, true, false), defaults.map { it.enabled })
        assertEquals(listOf(1L, 2L, 3L, 4L), defaults.map { it.userOrder })
        assertTrue(defaults.all { it.port.isEmpty() && it.network.isEmpty() })
        val json = buildConfigWithRules(defaults)
        val declarations = json.getAsJsonObject("route").getAsJsonArray("rule_set").map { it.asJsonObject }
        assertEquals(3, declarations.size)
        val sourceByTag = declarations.associate { it["tag"].asString to it["url"].asString }
        val rules = routes(json).filter { it.has("rule_set") }
        assertEquals(3, rules.size)
        assertEquals(listOf("reject", "route", "route"), rules.map { it["action"]?.asString ?: "route" })
        assertEquals("selected", rules[1]["outbound"].asString)
        assertEquals("direct", rules[2]["outbound"].asString)
        assertEquals(listOf("geosite-category-ads-all.srs", "google.srs", "geosite-cn.srs"),
            rules.map { rule ->
                val tag = rule["rule_set"].let { if (it.isJsonArray) it.asJsonArray[0].asString else it.asString }
                sourceByTag.getValue(tag).substringAfterLast('/')
            })
        // Existing DNS projection limitations must not be hidden by the new presets.
        assertTrue(dnsRules(json).isEmpty())
        assertEquals("selected", json.getAsJsonObject("route")["final"].asString)
        defaults.last().enabled = true
        val withIp = buildConfigWithRules(defaults)
        assertEquals(4, withIp.getAsJsonObject("route").getAsJsonArray("rule_set").size())
        assertEquals("direct", routes(withIp).last()["outbound"].asString)
    }

    @Test fun nativeReferencesAreDeclaredAndUsedWithoutBasenameCollisions() {
        val first = ref("same")
        val second = first.copy(source = "https://another.example/same.srs")
        val json = buildConfigWithRules(listOf(sets(nativeRule(), first, second)))
        val declared = json.getAsJsonObject("route").getAsJsonArray("rule_set").map { it.asJsonObject }
        assertEquals(2, declared.size)
        assertEquals(2, declared.map { it["tag"].asString }.toSet().size)
        assertEquals(setOf(first.source, second.source), declared.map { it["url"].asString }.toSet())
        declared.forEach {
            // Pinned sing-box omits format when the .srs URL determines binary.
            val inferred = if (java.net.URI(it["url"].asString).path.endsWith(".srs")) "binary" else null
            assertEquals("binary", it["format"]?.asString ?: inferred)
            val client = it.getAsJsonObject("http_client")
            assertEquals("dns-direct", client["domain_resolver"].asString)
            assertFalse(client.has("detour"))
            assertTrue(routes(json).toString().contains(it["tag"].asString))
        }
    }

    @Test fun realSnapshotCompilesLocalBinaryRuleSetWithoutRemoteOptions() {
        val path = File(System.getProperty("java.io.tmpdir"), "vialen-local-regression.srs").absolutePath
        val local = io.nekohasekai.sagernet.database.RouteRuleSet("local", path, format = "binary")
        // Executes production ConfigSnapshot.capture -> CoreClient -> Go compiler.
        // Compilation validates paths and references without reading the SRS file.
        val config = buildConfigWithRules(listOf(sets(nativeRule(), local)))
        val declared = config.getAsJsonObject("route").getAsJsonArray("rule_set").single().asJsonObject
        assertEquals("local", declared["type"].asString)
        assertEquals(path, declared["path"].asString)
        assertFalse(declared.has("url"))
        assertFalse(declared.has("http_client"))
        assertFalse(declared.has("download_detour"))
        assertFalse(declared.has("initial_path"))
        val tag = declared["tag"].asString
        assertTrue(routes(config).any { it["rule_set"]?.toString()?.contains(tag) == true })
        assertTrue(dnsRules(config).isEmpty())
    }

    @Test fun connectionOnlyRulesSurviveAndNeverBecomeDnsBlocks() {
        for (r in listOf(nativeRule().apply { sourcePort = "1234" }, nativeRule().apply { network = "tcp" }, nativeRule().apply { protocol = "tls" })) {
            val json = buildConfigWithRules(listOf(r))
            assertTrue(routes(json).any { it["outbound"]?.asString == "direct" })
        }
        val first = nativeRule().apply { domains = "example.com"; port = "443" }
        val later = nativeRule().apply { id = 101 }.apply { domains = "example.com"; outbound = -2 }
        val json = buildConfigWithRules(listOf(first, later))
        assertFalse(dnsRules(json).any { it["action"]?.asString == "reject" })
    }

    @Test fun pureDomainDnsStillWorksButAddressSetsUseConnectionRouting() {
        val simple = buildConfigWithRules(listOf(nativeRule().apply { domains = "full:blocked.example"; outbound = -2 }))
        assertTrue(dnsRules(simple).any { it["action"]?.asString == "reject" })
        for (r in listOf(sets(nativeRule(), ref("geoip-cn")), nativeRule().apply { ipIsPrivate = true }, nativeRule().apply { ip = "203.0.113.0/24" })) {
            val json = buildConfigWithRules(listOf(r))
            assertFalse(dnsRules(json).any { it["action"]?.asString in listOf("evaluate", "reject") || it.has("match_response") })
        }
    }

    @Test fun invalidInputAndAmbiguousCustomConditionsAreRejected() {
        for (r in listOf(
            nativeRule().apply { domains = "geosite:cn" },
            nativeRule().apply { ip = "geoip:cn" },
            nativeRule().apply { domains = "example.com"; port = "invalid" },
            nativeRule().apply { sourcePort = "65536" },
            nativeRule().apply { port = "500:100" },
            nativeRule().apply { domains = "example.com"; config = """{"type":"logical","mode":"and","rules":[{"port":[443]}]}""" }
        )) {
            try { buildConfigWithRules(listOf(r)); org.junit.Assert.fail("Invalid rule must fail: " + r) }
            catch (e: RuntimeException) { assertTrue("A rejection must explain its cause", e.message.orEmpty().isNotBlank()) }
        }
    }

    private fun conditions(rule: JsonObject): List<JsonObject> =
        listOf(rule) + rule.getAsJsonArray("rules")?.flatMap { conditions(it.asJsonObject) }.orEmpty()

    @Test fun generatedRulesRetainExplicitAndConditionsAndPriority() {
        val domainAndIP = nativeRule().apply {
            domains = "full:cn.example"
            ip = "203.0.113.0/24"
            port = "443"
            outbound = -2
        }
        val fallback = nativeRule().apply { id = 101; userOrder = 1; network = "tcp" }
        val json = buildConfigWithRules(listOf(domainAndIP, fallback))
        val userRules = routes(json).filter { it["action"]?.asString == "reject" || it.has("outbound") }
        assertEquals(2, userRules.size)
        val first = userRules.first()
        assertEquals("reject", first["action"].asString)
        assertEquals("logical", first["type"].asString)
        assertEquals("and", first["mode"].asString)
        val leaves = conditions(first)
        assertTrue(leaves.any { it["domain"]?.toString()?.contains("cn.example") == true })
        assertTrue(leaves.any { it["ip_cidr"]?.toString()?.contains("203.0.113.0/24") == true })
        assertTrue(leaves.any { it["port"]?.toString()?.contains("443") == true })
        assertEquals("direct", userRules.last()["outbound"].asString)
        assertFalse(dnsRules(json).any { it["action"]?.asString == "reject" })
    }

    @Test fun ruleSetReferencesRemainAndedWithConnectionConditions() {
        val rule = sets(nativeRule().apply { port = "443"; sourcePort = "1234"; ip = "203.0.113.0/24" }, ref("geosite-cn"))
        val json = buildConfigWithRules(listOf(rule))
        val declared = json.getAsJsonObject("route").getAsJsonArray("rule_set").single().asJsonObject["tag"].asString
        val route = routes(json).single { it["outbound"]?.asString == "direct" }
        assertEquals("and", route["mode"].asString)
        val leaves = conditions(route)
        assertTrue(leaves.any { it["rule_set"]?.toString()?.contains(declared) == true })
        for ((field, value) in listOf("port" to "443", "source_port" to "1234", "ip_cidr" to "203.0.113.0/24")) {
            assertTrue("Missing connection condition $field", leaves.any { it[field]?.toString()?.contains(value) == true })
        }
        assertTrue(dnsRules(json).isEmpty())
    }

    @Test fun mixedRuleSetDirectionsKeepAdjacentAlternativesAndBothPortConditions() {
        val rule = sets(nativeRule().apply { port = "443"; sourcePort = "1234" }, ref("geoip-source", "source"), ref("geoip-destination"))
        val json = buildConfigWithRules(listOf(rule))
        val alternatives = routes(json).filter { it["outbound"]?.asString == "direct" }
        assertEquals(2, alternatives.size)
        assertEquals(setOf(false, true), alternatives.map { route ->
            val set = conditions(route).single { it.has("rule_set") }
            set["rule_set_ip_cidr_match_source"]?.asBoolean ?: false
        }.toSet())
        alternatives.forEach { route ->
            assertEquals("and", route["mode"].asString)
            val leaves = conditions(route)
            assertTrue(leaves.any { it["port"]?.toString()?.contains("443") == true })
            assertTrue(leaves.any { it["source_port"]?.toString()?.contains("1234") == true })
        }
        assertTrue(dnsRules(json).isEmpty())
    }
}
