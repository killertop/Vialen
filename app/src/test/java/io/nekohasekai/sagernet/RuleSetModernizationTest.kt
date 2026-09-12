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

    private fun buildConfigWithRules(rules: List<RuleEntity>): Pair<SingBoxOptions.MyOptions, JsonObject> {
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
        val options = gson.fromJson(result.config, SingBoxOptions.MyOptions::class.java)
        val json = JsonParser.parseString(result.config).asJsonObject
        return Pair(options, json)
    }

    private fun dnsRules(json: JsonObject): List<JsonObject> {
        return json.getAsJsonObject("dns").getAsJsonArray("rules").map { it.asJsonObject }
    }

    private fun nativeRule() = RuleEntity(id = 100, enabled = true, outbound = -1)
    private fun ref(name: String, match: String = "destination") =
        io.nekohasekai.sagernet.database.RouteRuleSet(name, "https://example.net/" + name + ".srs", match = match)
    private fun sets(rule: RuleEntity, vararg refs: io.nekohasekai.sagernet.database.RouteRuleSet) = rule.apply {
        ruleSets = io.nekohasekai.sagernet.database.RouteRuleSet.encode(refs.toList())
    }
    private fun routes(json: JsonObject) = json.getAsJsonObject("route").getAsJsonArray("rules").map { it.asJsonObject }

    @Test fun nativeReferencesAreDeclaredAndUsedWithoutBasenameCollisions() {
        val first = ref("same")
        val second = first.copy(source = "https://another.example/same.srs")
        val (_, json) = buildConfigWithRules(listOf(sets(nativeRule(), first, second)))
        val declared = json.getAsJsonObject("route").getAsJsonArray("rule_set").map { it.asJsonObject }
        assertEquals(2, declared.size)
        assertEquals(2, declared.map { it["tag"].asString }.toSet().size)
        declared.forEach {
            assertEquals("binary", it["format"].asString)
            assertEquals("default-http-client", it["http_client"].asString)
            assertTrue(routes(json).toString().contains(it["tag"].asString))
        }
    }

    @Test fun connectionOnlyRulesSurviveAndNeverBecomeDnsBlocks() {
        for (r in listOf(nativeRule().apply { sourcePort = "1234" }, nativeRule().apply { network = "tcp" }, nativeRule().apply { protocol = "tls" })) {
            val (_, json) = buildConfigWithRules(listOf(r))
            assertTrue(routes(json).any { it["outbound"]?.asString == "bypass" })
        }
        val first = nativeRule().apply { domains = "example.com"; port = "443" }
        val later = nativeRule().apply { domains = "example.com"; outbound = -2 }
        val (_, json) = buildConfigWithRules(listOf(first, later))
        assertFalse(dnsRules(json).any { it["action"]?.asString == "reject" })
    }

    @Test fun pureDomainDnsStillWorksButAddressSetsUseConnectionRouting() {
        val (_, simple) = buildConfigWithRules(listOf(nativeRule().apply { domains = "full:blocked.example"; outbound = -2 }))
        assertTrue(dnsRules(simple).any { it["action"]?.asString == "reject" })
        for (r in listOf(sets(nativeRule(), ref("geoip-cn")), nativeRule().apply { ipIsPrivate = true }, nativeRule().apply { ip = "203.0.113.0/24" })) {
            val (_, json) = buildConfigWithRules(listOf(r))
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
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().startsWith("Rule ")) }
        }
    }

    /** Golden cases are generated through RuleEntity -> ConfigSnapshot -> Rust JNI.
     * The Go test reads the SAME checked-in configurations and replaces only set
     * data and host-incompatible transports, never the generated user rules. */
    @Test fun generatedConfigurationsMatchCoreFixtures() {
        val cases = com.google.gson.JsonArray()
        fun add(name: String, rule: RuleEntity, checks: String, extra: List<RuleEntity> = emptyList()) {
            val (_, config) = buildConfigWithRules(listOf(rule) + extra)
            cases.add(JsonObject().apply {
                addProperty("name", name)
                add("config", config)
                add("checks", JsonParser.parseString(checks))
            })
        }
        val matrix = """[
            {"domain":"cn.example","source":"192.0.2.1:1234","destination":"8.8.8.8:443","want":"bypass"},
            {"domain":"other.example","source":"192.0.2.1:1234","destination":"203.0.113.1:443","want":"bypass"},
            {"domain":"cn.example","source":"192.0.2.1:1234","destination":"203.0.113.1:443","want":"bypass"},
            {"domain":"other.example","source":"192.0.2.1:1234","destination":"8.8.8.8:443","want":"proxy"},
            {"domain":"cn.example","source":"192.0.2.1:1234","destination":"203.0.113.1:80","want":"proxy"}
        ]"""
        add("domain-set-and-ip-set", sets(nativeRule().apply { port = "443" }, ref("geosite-cn"), ref("geoip-cn")), matrix)
        add("domain-set-and-cidr", sets(nativeRule().apply { port = "443"; ip = "203.0.113.0/24" }, ref("geosite-cn")), matrix)
        add("domain-set-and-private", sets(nativeRule().apply { port = "443"; ipIsPrivate = true }, ref("geosite-cn")), matrix.replace("203.0.113.1", "10.0.0.1"))
        add("native-domain-and-ip", nativeRule().apply { domains = "full:cn.example"; ip = "203.0.113.0/24"; port = "443" }, matrix)
        add("source-destination-isolation", sets(nativeRule().apply { port = "443"; sourcePort = "1234" }, ref("geoip-source", "source"), ref("geoip-cn")), """[
            {"source":"192.168.10.1:1234","destination":"203.0.113.1:443","want":"bypass"},
            {"source":"203.0.113.1:1234","destination":"192.168.10.1:443","want":"proxy"},
            {"source":"192.168.10.1:1234","destination":"8.8.8.8:443","want":"proxy"},
            {"source":"192.168.10.1:4321","destination":"203.0.113.1:443","want":"proxy"},
            {"source":"192.168.10.1:1234","destination":"203.0.113.1:80","want":"proxy"},
            {"source":"192.0.2.1:1234","destination":"203.0.113.1:443","want":"proxy"}
        ]""")
        add("source-set-or-cidr", sets(nativeRule().apply { source = "192.0.2.0/24"; ip = "203.0.113.0/24" }, ref("geoip-source", "source")), """[
            {"source":"192.168.10.1:1234","destination":"203.0.113.1:443","want":"bypass"},
            {"source":"192.0.2.1:1234","destination":"203.0.113.1:443","want":"bypass"},
            {"source":"198.51.100.1:1234","destination":"203.0.113.1:443","want":"proxy"},
            {"source":"192.0.2.1:1234","destination":"8.8.8.8:443","want":"proxy"}
        ]""")
        add("whole-rule-invert", sets(nativeRule().apply { port = "443"; config = """{"invert":true}""" }, ref("geosite-cn"), ref("geoip-cn")),
            matrix.replace("\"bypass\"", "\"TEMP\"").replace("\"proxy\"", "\"bypass\"").replace("\"TEMP\"", "\"proxy\""))
        add("custom-port-replacement", sets(nativeRule().apply { port = "80"; config = """{"port":[443]}""" }, ref("geosite-cn"), ref("geoip-cn")), matrix)
        add("custom-port-append", sets(nativeRule().apply { port = "80"; config = """{"port+":[443]}""" }, ref("geosite-cn"), ref("geoip-cn")),
            matrix.replace("\"destination\":\"203.0.113.1:80\",\"want\":\"proxy\"", "\"destination\":\"203.0.113.1:80\",\"want\":\"bypass\""))
        add("explicit-custom-and", nativeRule().apply { config = """{"type":"logical","mode":"and","rules":[{"domain":["cn.example"]},{"ip_cidr":["203.0.113.0/24"]}]}""" }, """[
            {"domain":"cn.example","destination":"203.0.113.1:443","want":"bypass"},
            {"domain":"cn.example","destination":"8.8.8.8:443","want":"proxy"},
            {"domain":"other.example","destination":"203.0.113.1:443","want":"proxy"}
        ]""")
        add("network-only", nativeRule().apply { network = "udp" }, """[
            {"network":"udp","destination":"203.0.113.1:443","want":"bypass"},
            {"network":"tcp","destination":"203.0.113.1:443","want":"proxy"}
        ]""")
        add("protocol-only", nativeRule().apply { protocol = "tls" }, """[
            {"protocol":"tls","destination":"203.0.113.1:443","want":"bypass"},
            {"protocol":"http","destination":"203.0.113.1:443","want":"proxy"}
        ]""")
        add("source-port-only", nativeRule().apply { sourcePort = "1234" }, """[
            {"source":"192.0.2.1:1234","destination":"203.0.113.1:443","want":"bypass"},
            {"source":"192.0.2.1:4321","destination":"203.0.113.1:443","want":"proxy"}
        ]""")
        add("first-rule-priority", nativeRule().apply { domains = "cn.example"; outbound = -2 }, """[
            {"domain":"cn.example","destination":"203.0.113.1:443","want":"reject"},
            {"domain":"other.example","destination":"203.0.113.1:443","want":"bypass"}
        ]""", listOf(nativeRule().apply { ip = "203.0.113.0/24" }))
        add("same-set-both-directions", sets(nativeRule(), ref("geoip-cn", "source"), ref("geoip-cn")), """[
            {"source":"203.0.113.1:1234","destination":"203.0.113.2:443","want":"bypass"},
            {"source":"203.0.113.1:1234","destination":"8.8.8.8:443","want":"proxy"},
            {"source":"192.0.2.1:1234","destination":"203.0.113.2:443","want":"proxy"}
        ]""")
        add("source-set-or-private", sets(nativeRule().apply { sourceIpIsPrivate = true; ip = "203.0.113.0/24" }, ref("geoip-cn", "source")), """[
            {"source":"203.0.113.1:1234","destination":"203.0.113.2:443","want":"bypass"},
            {"source":"10.0.0.1:1234","destination":"203.0.113.2:443","want":"bypass"},
            {"source":"192.0.2.1:1234","destination":"203.0.113.2:443","want":"proxy"}
        ]""")
        add("additional-set-constraint", sets(nativeRule().apply { ip = "203.0.113.0/24" }, ref("geosite-cn", "rule")), """[
            {"domain":"cn.example","destination":"203.0.113.2:443","want":"bypass"},
            {"domain":"other.example","destination":"203.0.113.2:443","want":"proxy"},
            {"domain":"cn.example","destination":"8.8.8.8:443","want":"proxy"}
        ]""")
        for (enabled in listOf(false, true)) {
            add(if (enabled) "transport-block" else "transport-baseline", nativeRule().apply { ip = "127.0.0.1/32" },
                """[{"destination":"127.0.0.1:443","want":"bypass"},{"destination":"198.51.100.1:443","want":"proxy"},{"destination":"198.51.100.2:443","want":"RESULT"}]""".replace("RESULT", if (enabled) "reject" else "proxy"),
                listOf(nativeRule().apply { ip = "198.51.100.2/32"; outbound = -2; this.enabled = enabled }))
        }
        val output = File("build/route-semantics/generated.json").apply { parentFile!!.mkdirs() }
        output.writeText(cases.toString())
        val golden = File("src/test/resources/native-route-semantics.json")
        if (System.getenv("UPDATE_NATIVE_RULE_FIXTURE") == "1") golden.writeText(cases.toString())
        assertTrue("Generate the native rule fixture explicitly", golden.isFile)
        assertEquals(JsonParser.parseString(golden.readText()), cases)
    }
}
