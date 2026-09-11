package io.nekohasekai.sagernet

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.RouteRuleSet
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptionsUtil
import moe.matsuri.nb4a.generateRuleSet
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.TreeMap

class ConfigBuilderGoldenFixtureTest {

    companion object {
        private val prettyGson = GsonBuilder().setPrettyPrinting().create()
        private val rootDir: File by lazy {
            val cur = File(".").canonicalFile
            if (cur.name == "app") cur.parentFile else cur
        }
        private val fixtureDir: File by lazy {
            File(rootDir, "app/src/test/resources/config-v1.14")
        }

        @BeforeClass
        @JvmStatic
        fun setupDir() {
            fixtureDir.mkdirs()
            io.mockk.mockkStatic(android.widget.Toast::class)
            every { android.widget.Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)
            every { android.widget.Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)

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
            every { DataStore.remoteDns } returns "tls://8.8.8.8\nhttps://1.1.1.1/dns-query"
            every { DataStore.directDns } returns "223.5.5.5\n119.29.29.29"
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
            val mockRuleDao = mockk<RuleEntity.Dao>(relaxed = true)
            every { mockRuleDao.enabledRules() } returns listOf()

            mockkObject(SagerDatabase.Companion)
            io.nekohasekai.sagernet.installConfigSnapshotTransactions()
            every { SagerDatabase.groupDao } returns mockGroupDao
            every { SagerDatabase.proxyDao } returns mockProxyDao
            every { SagerDatabase.rulesDao } returns mockRuleDao
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            unmockkAll()
        }

        fun canonicalize(jsonStr: String): String {
            val element = JsonParser.parseString(jsonStr)
            return prettyGson.toJson(sortJsonElement(element))
        }

        private fun sortJsonElement(element: JsonElement): JsonElement {
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                val sortedMap = TreeMap<String, JsonElement>()
                for (entry in obj.entrySet()) {
                    sortedMap[entry.key] = sortJsonElement(entry.value)
                }
                val sortedObj = com.google.gson.JsonObject()
                for ((key, value) in sortedMap) {
                    sortedObj.add(key, value)
                }
                return sortedObj
            } else if (element.isJsonArray) {
                val array = element.asJsonArray
                val sortedArray = com.google.gson.JsonArray()
                for (item in array) {
                    sortedArray.add(sortJsonElement(item))
                }
                return sortedArray
            }
            return element
        }
    }

    @Test
    fun testGoldenFixtureSetCompleteness() {
        val expectedSet = setOf(
            "config_custom_direct.json",
            "full_default_config_1_12.json",
            "full_ruleset_config_1_12.json",
            "full_wireguard_config_1_12.json",
            "full_ruleset_shared_http_client.json",
            "outbound_anytls.json",
            "outbound_chain.json",
            "outbound_http.json",
            "outbound_hysteria1.json",
            "outbound_hysteria2.json",
            "outbound_hysteria2_default_1_14.json",
            "outbound_hysteria2_bbr_conservative.json",
            "outbound_hysteria2_bbr_standard.json",
            "outbound_hysteria2_bbr_aggressive.json",
            "outbound_hysteria2_disable_chrome_parrot.json",
            "outbound_hysteria2_port_hop_max.json",
            "outbound_hysteria2_gecko.json",
            "outbound_shadowsocks.json",
            "outbound_shadowtls.json",
            "outbound_socks5.json",
            "outbound_trojan.json",
            "outbound_tuic.json",
            "outbound_vless_reality.json",
            "outbound_vmess.json",
            "outbound_wireguard.json"
        )
        val actualSet = fixtureDir.listFiles { f -> f.extension == "json" }
            ?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(expectedSet, actualSet)
    }

    private fun assertOrSaveFixture(name: String, generatedObj: Any) {
        val json = if (generatedObj is String) generatedObj else gson.toJson(generatedObj)
        val canonical = canonicalize(json)
        val fixtureFile = File(fixtureDir, "$name.json")
        if (!fixtureFile.exists() || (name == "full_ruleset_config_1_12" && System.getenv("UPDATE_NATIVE_RULE_FIXTURE") == "1")) {
            fixtureFile.writeText(canonical)
        }
        val expected = canonicalize(fixtureFile.readText())
        assertEquals("Config fixture mismatch for $name", expected, canonical)
    }

    // -------------------------------------------------------------
    // Outbound Baseline Fixtures
    // -------------------------------------------------------------
    @Test
    fun testSocks5OutboundFixture() {
        val bean = SOCKSBean().applyDefaultValues().apply {
            serverAddress = "192.168.1.100"
            serverPort = 1080
            username = "testuser"
            password = "testpass"
            protocol = SOCKSBean.PROTOCOL_SOCKS5
        }
        val outbound = buildSingBoxOutboundSocksBean(bean)
        assertOrSaveFixture("outbound_socks5", outbound)
    }

    @Test
    fun testHttpOutboundFixture() {
        val bean = HttpBean().applyDefaultValues().apply {
            serverAddress = "proxy.example.com"
            serverPort = 8443
            username = "admin"
            password = "secret"
            security = "tls"
            sni = "proxy.example.com"
        }
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean)
        assertOrSaveFixture("outbound_http", outbound)
    }

    @Test
    fun testShadowsocksOutboundFixture() {
        val bean = ShadowsocksBean().applyDefaultValues().apply {
            serverAddress = "ss.example.com"
            serverPort = 8388
            method = "chacha20-ietf-poly1305"
            password = "mypassword"
        }
        val outbound = buildSingBoxOutboundShadowsocksBean(bean)
        assertOrSaveFixture("outbound_shadowsocks", outbound)
    }

    @Test
    fun testVMessOutboundFixture() {
        val bean = VMessBean().applyDefaultValues().apply {
            serverAddress = "vmess.example.com"
            serverPort = 443
            uuid = "a3424107-160a-4286-9051-7d1c5a93b482"
            alterId = 0
            encryption = "auto"
            type = "ws"
            host = "vmess.example.com"
            path = "/websocket"
            security = "tls"
            sni = "vmess.example.com"
        }
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean)
        assertOrSaveFixture("outbound_vmess", outbound)
    }

    @Test
    fun testVLESSRealityOutboundFixture() {
        val bean = VMessBean().applyDefaultValues().apply {
            serverAddress = "vless.example.com"
            serverPort = 443
            uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
            alterId = -1
            type = "tcp"
            security = "tls"
            sni = "yahoo.com"
            encryption = "xtls-rprx-vision"
            realityPubKey = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"
            realityShortId = "0123456789abcdef"
            utlsFingerprint = "chrome"
        }
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean)
        assertOrSaveFixture("outbound_vless_reality", outbound)
    }

    @Test
    fun testTrojanOutboundFixture() {
        val bean = TrojanBean().applyDefaultValues().apply {
            serverAddress = "trojan.example.com"
            serverPort = 443
            password = "trojanpassword"
            security = "tls"
            sni = "trojan.example.com"
            type = "tcp"
        }
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean)
        assertOrSaveFixture("outbound_trojan", outbound)
    }

    @Test
    fun testHysteria1OutboundFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 1
            serverAddress = "hy1.example.com"
            serverPorts = "36712"
            authPayload = "myhyauth"
            uploadMbps = 100
            downloadMbps = 200
            sni = "hy1.example.com"
            protocol = HysteriaBean.PROTOCOL_UDP
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria1", outbound)
    }

    @Test
    fun testHysteria2OutboundFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            obfuscation = "obfspassword"
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2", outbound)
    }

    @Test
    fun testTuicOutboundFixture() {
        val bean = TuicBean().applyDefaultValues().apply {
            protocolVersion = 5
            serverAddress = "tuic.example.com"
            serverPort = 8443
            uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
            token = "tuicpassword"
            congestionController = "bbr"
            sni = "tuic.example.com"
        }
        val outbound = buildSingBoxOutboundTuicBean(bean)
        assertOrSaveFixture("outbound_tuic", outbound)
    }

    @Test
    fun testWireGuardOutboundFixture() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            serverAddress = "wg.example.com"
            serverPort = 51820
            localAddress = "10.0.0.2/32\nfd00::2/128"
            privateKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            peerPublicKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            mtu = 1420
        }
        val outbound = buildSingBoxOutboundWireguardBean(bean)
        assertOrSaveFixture("outbound_wireguard", outbound)
    }

    @Test
    fun testShadowTLSOutboundFixture() {
        val bean = ShadowTLSBean().applyDefaultValues().apply {
            serverAddress = "stls.example.com"
            serverPort = 443
            password = "shadowpass"
            sni = "gateway.icloud.com"
            version = 3
        }
        val outbound = buildSingBoxOutboundShadowTLSBean(bean)
        assertOrSaveFixture("outbound_shadowtls", outbound)
    }

    @Test
    fun testAnyTLSOutboundFixture() {
        val bean = AnyTLSBean().applyDefaultValues().apply {
            serverAddress = "anytls.example.com"
            serverPort = 443
            password = "anypassword"
            sni = "anytls.example.com"
        }
        val outbound = buildSingBoxOutboundAnyTLSBean(bean)
        assertOrSaveFixture("outbound_anytls", outbound)
    }

    @Test
    fun testChainOutboundFixture() {
        val ssEntity = ProxyEntity().apply {
            id = 101L
            groupId = 1L
            type = ProxyEntity.TYPE_SS
            ssBean = ShadowsocksBean().applyDefaultValues().apply {
                serverAddress = "front.ss.example.com"
                serverPort = 8388
                method = "chacha20-ietf-poly1305"
                password = "frontpassword"
                name = "FrontSS"
            }
        }
        val anyEntity = ProxyEntity().apply {
            id = 102L
            groupId = 1L
            type = ProxyEntity.TYPE_ANYTLS
            anyTLSBean = AnyTLSBean().applyDefaultValues().apply {
                serverAddress = "landing.anytls.example.com"
                serverPort = 443
                password = "landingpass"
                sni = "landing.anytls.example.com"
                name = "LandingAnyTLS"
            }
        }
        val chainEntity = ProxyEntity().apply {
            id = 100L
            groupId = 1L
            type = ProxyEntity.TYPE_CHAIN
            chainBean = ChainBean().applyDefaultValues().apply {
                name = "TwoHopChain"
                proxies = mutableListOf(101L, 102L)
            }
        }

        every { SagerDatabase.proxyDao.getById(101L) } returns ssEntity
        every { SagerDatabase.proxyDao.getById(102L) } returns anyEntity
        every { SagerDatabase.proxyDao.getEntities(listOf(101L, 102L)) } returns listOf(ssEntity, anyEntity)
        every { SagerDatabase.proxyDao.getEntities(listOf(102L, 101L)) } returns listOf(anyEntity, ssEntity)

        val res = buildConfig(chainEntity, forTest = true)
        assertOrSaveFixture("outbound_chain", res.config)
    }

    @Test
    fun testCustomConfigFixture() {
        val raw = """
            {
                "outbounds": [
                    {
                        "type": "direct",
                        "tag": "direct"
                    }
                ]
            }
        """.trimIndent()
        val bean = ConfigBean().applyDefaultValues().apply {
            name = "CustomDirect"
            config = raw
        }
        assertOrSaveFixture("config_custom_direct", bean.config)
    }

    // -------------------------------------------------------------
    // Full System Configuration Fixtures (DNS / TUN / Route / Ruleset)
    // -------------------------------------------------------------
    @Test
    fun testFullDefaultConfigFixture() {
        val ssEntity = ProxyEntity().apply {
            id = 1L
            groupId = 1L
            type = ProxyEntity.TYPE_SS
            ssBean = ShadowsocksBean().applyDefaultValues().apply {
                serverAddress = "ss.baseline.internal"
                serverPort = 8388
                method = "chacha20-ietf-poly1305"
                password = "mypassword"
                name = "DefaultShadowsocks"
            }
        }

        every { SagerDatabase.proxyDao.getById(1L) } returns ssEntity
        every { SagerDatabase.proxyDao.getEntities(any()) } returns listOf(ssEntity)

        val res = buildConfig(ssEntity, forTest = false, forExport = false)
        assertOrSaveFixture("full_default_config_1_12", res.config)
    }

    @Test
    fun testFullRulesetConfigFixture() {
        val ssEntity = ProxyEntity().apply {
            id = 1L
            groupId = 1L
            type = ProxyEntity.TYPE_SS
            ssBean = ShadowsocksBean().applyDefaultValues().apply {
                serverAddress = "ss.baseline.internal"
                serverPort = 8388
                method = "chacha20-ietf-poly1305"
                password = "mypassword"
                name = "DefaultShadowsocks"
            }
        }
        val rule1 = RuleEntity().apply {
            id = 10L
            name = "BypassChina"
            enabled = true
            outbound = -1L // bypass / direct
            domains = "domain:cn"
            ipIsPrivate = true
            ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official("geosite", "cn"), RouteRuleSet.official("geoip", "cn")))
        }
        val rule2 = RuleEntity().apply {
            id = 11L
            name = "BlockAds"
            enabled = true
            outbound = -2L // block
            ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official("geosite", "category-ads-all")))
        }

        every { SagerDatabase.proxyDao.getById(1L) } returns ssEntity
        every { SagerDatabase.proxyDao.getEntities(any()) } returns listOf(ssEntity)
        every { SagerDatabase.rulesDao.enabledRules() } returns listOf(rule1, rule2)

        val res = buildConfig(ssEntity, forTest = false, forExport = false)
        assertOrSaveFixture("full_ruleset_config_1_12", res.config)
    }

    @Test
    fun testFullWireGuardConfigFixture() {
        val wgEntity = ProxyEntity().apply {
            id = 2L
            groupId = 1L
            type = ProxyEntity.TYPE_WG
            wgBean = WireGuardBean().applyDefaultValues().apply {
                serverAddress = "wg.baseline.internal"
                serverPort = 51820
                localAddress = "10.0.0.2/32\nfd00::2/128"
                privateKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                peerPublicKey = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                mtu = 1420
                name = "DefaultWireGuard"
            }
        }

        every { SagerDatabase.proxyDao.getById(2L) } returns wgEntity
        every { SagerDatabase.proxyDao.getEntities(any()) } returns listOf(wgEntity)
        every { SagerDatabase.rulesDao.enabledRules() } returns listOf()

        val res = buildConfig(wgEntity, forTest = false, forExport = false)
        assertOrSaveFixture("full_wireguard_config_1_12", res.config)
    }

    // -------------------------------------------------------------
    // Phase C 1.14 Capabilities Fixtures
    // -------------------------------------------------------------
    @Test
    fun testHysteria2Default1_14Fixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            obfuscation = "obfspassword"
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_default_1_14", outbound)
    }

    @Test
    fun testHysteria2BBRConservativeFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            bbrProfile = "conservative"
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_bbr_conservative", outbound)
    }

    @Test
    fun testHysteria2BBRStandardFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            bbrProfile = "standard"
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_bbr_standard", outbound)
    }

    @Test
    fun testHysteria2BBRAggressiveFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            bbrProfile = "aggressive"
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_bbr_aggressive", outbound)
    }

    @Test
    fun testHysteria2DisableChromeParrotFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            disableChromeParrot = true
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_disable_chrome_parrot", outbound)
    }

    @Test
    fun testHysteria2PortHopMaxFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "20000:30000"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            hopInterval = 15
            hopIntervalMax = 45
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_port_hop_max", outbound)
    }

    @Test
    fun testHysteria2GeckoFixture() {
        val bean = HysteriaBean().applyDefaultValues().apply {
            protocolVersion = 2
            serverAddress = "hy2.example.com"
            serverPorts = "443"
            authPayload = "hy2password"
            sni = "hy2.example.com"
            obfsType = "gecko"
            obfuscation = "geckosecret"
            obfsMinPacketSize = 600
            obfsMaxPacketSize = 1300
        }
        val outbound = buildSingBoxOutboundHysteriaBean(bean)
        assertOrSaveFixture("outbound_hysteria2_gecko", outbound)
    }

    @Test
    fun testRulesetSharedHttpClientFixture() {
        val rules = listOf("https://example.com/rules.srs")
        val ruleSets = mutableListOf<SingBoxOptions.RuleSet>()
        generateRuleSet(rules, ruleSets)
        val config = SingBoxOptions.MyOptions().apply {
            log = SingBoxOptions.LogOptions().apply { level = "info" }
            outbounds = mutableListOf(SingBoxOptions.Outbound().apply {
                type = "direct"
                tag = "direct"
            })
            route = SingBoxOptions.RouteOptions().apply {
                rule_set = ruleSets
                default_http_client = "default-http-client"
            }
            http_clients = mutableListOf(
                SingBoxOptions.HTTPClient().apply {
                    tag = "default-http-client"
                    detour = "direct"
                }
            )
        }
        assertOrSaveFixture("full_ruleset_shared_http_client", config)
    }

    @Test
    fun testNoDownloadDetourInGeneratedConfigs() {
        for (f in fixtureDir.listFiles { f -> f.extension == "json" } ?: emptyArray()) {
            val content = f.readText()
            org.junit.Assert.assertFalse(
                "Config fixture ${f.name} must not contain deprecated download_detour",
                content.contains("download_detour")
            )
        }
    }
}
