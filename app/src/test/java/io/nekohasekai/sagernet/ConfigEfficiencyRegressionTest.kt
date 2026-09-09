package io.nekohasekai.sagernet

import com.google.gson.JsonParser
import io.mockk.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.*
import org.junit.Assert.*

class ConfigEfficiencyRegressionTest {
    @Before fun setup() = ConfigBuilderGoldenFixtureTest.setupDir()
    @After fun cleanup() = ConfigBuilderGoldenFixtureTest.tearDown()

    @Test fun directTreeMatchesSerializedBoundaryForEveryProfileAndOwnsItsValues() {
        val beans = listOf(SOCKSBean(), ShadowsocksBean(), TuicBean(), HttpBean(), TrojanBean(),
            VMessBean(), ShadowTLSBean(), HysteriaBean(), WireGuardBean(), AnyTLSBean(), ConfigBean())
        for (bean in beans) {
            bean.applyDefaultValues()
            bean.serverAddress = "<汉字😀>&\"\\\n.example"
            if (bean is ConfigBean) bean.config = """{"type":"direct","test":"<😀>"}"""
            val snapshot = checkNotNull(RustOutboundConfig.capture(bean, true))
            val wire = snapshot.javaClass.getDeclaredField("wire").apply { isAccessible = true }.get(snapshot) as String
            val expected = JsonParser.parseString(wire).asJsonObject["profile"]
            val tree = checkNotNull(RustOutboundConfig.captureProfileJson(bean))
            assertEquals(bean.javaClass.name, expected, tree)
            bean.serverAddress = "changed.example"
            assertEquals(expected, tree)
            tree.asJsonObject.addProperty("kind", "modified")
            assertNotEquals("modified", checkNotNull(RustOutboundConfig.captureProfileJson(bean)).asJsonObject["kind"].asString)
        }
    }

    @Test fun malformedUtf16AndUnsetFieldsRetainFallbackWhileValidPairsSurvive() {
        for (text in listOf("\uD800", "\uDC00", "\uD800x", "x\uDC00", "\uD800\uD800")) {
            val bean = SOCKSBean().applyDefaultValues().apply { password = text }
            assertNull(RustOutboundConfig.capture(bean, false))
            assertNull(RustOutboundConfig.captureProfileJson(bean))
        }
        val bean = SOCKSBean().applyDefaultValues().apply { password = "😀" }
        assertNotNull(RustOutboundConfig.captureProfileJson(bean))
        bean.password = null
        assertNull(RustOutboundConfig.capture(bean, false))
        assertNull(RustOutboundConfig.captureProfileJson(bean))
        val hysteria = HysteriaBean().applyDefaultValues().apply { protocol = null }
        assertFalse(checkNotNull(RustOutboundConfig.captureProfileJson(hysteria)).asJsonObject.has("protocol"))
    }

    @Test fun customEscapesAndSyntaxErrorsKeepTheStandaloneBoundaryBehavior() {
        for (config in listOf("{\"type\":\"direct\",\"x\":\"\\ud800\"}",
            "{\"type\":\"direct\",\"x\":\"\uD800\"}")) {
            val bean = ConfigBean().applyDefaultValues().apply { this.config = config }
            assertNull(RustOutboundConfig.capture(bean, false))
            assertNull(RustOutboundConfig.captureProfileJson(bean))
        }
        val bean = ConfigBean().applyDefaultValues().apply { config = "{" }
        val oldError = runCatching { RustOutboundConfig.capture(bean, false) }.exceptionOrNull()
        val newError = runCatching { RustOutboundConfig.captureProfileJson(bean) }.exceptionOrNull()
        assertNotNull(oldError)
        assertEquals(oldError!!::class, newError!!::class)
    }

    @Test fun discoveryQueueKeepsInitialRowsBeforeNewDependenciesAndDeduplicatesCycles() {
        fun node(id: Long, group: Long) = ProxyEntity(id=id, groupId=group).apply {
            putBean(SOCKSBean().applyDefaultValues())
        }
        val selected = ProxyEntity(id=1, groupId=1).apply {
            putBean(ChainBean().applyDefaultValues().apply { proxies = mutableListOf(4, 1, 4) })
        }
        val selector = node(2, 1)
        val front = node(3, 2)
        val child = node(4, 3)
        val landing = node(5, 4)
        val rows = listOf(selected, selector, front, child, landing)
        val groups = listOf(ProxyGroup(id=1,isSelector=true,frontProxy=3),
            ProxyGroup(id=2,landingProxy=5), ProxyGroup(id=3), ProxyGroup(id=4))
        val profiles = mockk<ProxyEntity.Dao>()
        val groupDao = mockk<ProxyGroup.Dao>()
        val rules = mockk<RuleEntity.Dao>()
        every { profiles.getById(any()) } answers { rows.find { it.id == firstArg<Long>() } }
        every { profiles.getEntities(any()) } answers { rows.filter { it.id in firstArg<List<Long>>() } }
        every { profiles.getByGroup(1) } returns listOf(selected, selector, selected)
        every { groupDao.getById(any()) } answers { groups.find { it.id == firstArg<Long>() } }
        every { rules.enabledRules() } returns emptyList()
        every { SagerDatabase.proxyDao } returns profiles
        every { SagerDatabase.groupDao } returns groupDao
        every { SagerDatabase.rulesDao } returns rules
        val snapshot = ConfigSnapshot.capture(selected,false,false)
        val wire = snapshot.javaClass.getDeclaredField("wire").apply { isAccessible=true }.get(snapshot) as String
        val request = JsonParser.parseString(wire).asJsonObject
        assertEquals(listOf(1L,2L,3L,4L,5L), request["profiles"].asJsonArray.map { it.asJsonObject["id"].asLong })
        assertEquals(listOf(1L,2L,3L,4L), request["groups"].asJsonArray.map { it.asJsonObject["id"].asLong })
        verify(exactly=1) { profiles.getById(3) }
        verify(exactly=1) { profiles.getById(5) }
        verify(exactly=1) { profiles.getEntities(listOf(4L,1L,4L)) }
        verifyOrder { groupDao.getById(1); groupDao.getById(2); groupDao.getById(3); groupDao.getById(4) }
    }
}
