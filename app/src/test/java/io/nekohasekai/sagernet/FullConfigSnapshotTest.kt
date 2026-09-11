package io.nekohasekai.sagernet

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import io.mockk.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.oracle.buildLegacyConfig
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.*
import org.junit.Assert.*

class FullConfigSnapshotTest {
    @Before fun setup() = ConfigBuilderGoldenFixtureTest.setupDir()
    @After fun cleanup() = ConfigBuilderGoldenFixtureTest.tearDown()
    private fun node(id:Long, host:String="127.0.0.1") = ProxyEntity(id=id,groupId=1).apply {
        putBean(SOCKSBean().applyDefaultValues().apply { serverAddress=host;serverPort=1080;name="Node $id" })
    }
    private fun repository(nodes:List<ProxyEntity>,groups:List<ProxyGroup>,rules:List<RuleEntity>) {
        val profiles=mockk<ProxyEntity.Dao>();val groupDao=mockk<ProxyGroup.Dao>();val ruleDao=mockk<RuleEntity.Dao>()
        every { profiles.getById(any()) } answers { nodes.find { it.id==firstArg<Long>() } }
        every { profiles.getEntities(any()) } answers { nodes.filter { it.id in firstArg<List<Long>>() } }
        every { profiles.getByGroup(any()) } answers { nodes.filter { it.groupId==firstArg<Long>() } }
        every { groupDao.getById(any()) } answers { groups.find { it.id==firstArg<Long>() } }
        every { ruleDao.enabledRules() } returns rules
        every { SagerDatabase.proxyDao } returns profiles;every { SagerDatabase.groupDao } returns groupDao;every { SagerDatabase.rulesDao } returns ruleDao
    }
    // These fields match sets; sequence remains significant everywhere else (rules,
    // chain detours, selector outbounds, custom arrays, DNS evaluate/response pairs).
    private fun semantic(value:JsonElement,key:String=""):JsonElement {
        if(value.isJsonObject) return JsonObject().apply { value.asJsonObject.entrySet().forEach { (k,v)->add(k,semantic(v,k)) } }
        if(value.isJsonArray) {
            val children=value.asJsonArray.map { semantic(it) }
            val sorted=if(key in setOf("domain","domain_suffix","domain_keyword","domain_regex","user_id")) children.sortedBy { it.toString() } else children
            return JsonArray().apply { sorted.forEach(::add) }
        }
        return value
    }
    private fun compare(node:ProxyEntity,test:Boolean=false,export:Boolean=false) {
        val old=buildLegacyConfig(node,test,export);val fresh=ConfigSnapshot.capture(node,test,export).generate().result
        assertEquals(semantic(JsonParser.parseString(old.config)),semantic(JsonParser.parseString(fresh.config)))
        assertEquals(old.mainEntId,fresh.mainEntId);assertEquals(old.selectorGroupId,fresh.selectorGroupId)
        assertEquals(old.profileTagMap,fresh.profileTagMap)
        assertEquals(old.profileTagMap.keys.toList(),fresh.profileTagMap.keys.toList())
        assertEquals(old.trafficMap.keys.toList(),fresh.trafficMap.keys.toList())
        assertEquals(old.trafficMap.mapValues { (_,v)->v.map { it.id } },fresh.trafficMap.mapValues { (_,v)->v.map { it.id } })
    }
    @Test fun settingsAndDnsServerMatrixMatchesLegacy() {
        val selected=node(1,"node.example")
        repository(listOf(selected),listOf(ProxyGroup(id=1)),emptyList())
        val dns=listOf("local","1.1.1.1","udp://1.1.1.1:5353","tls://[2001:db8::1]:8853","tcp://dns.example:54","quic://dns.example","h3://dns.example:444","https://dns.example/dns-query","https://dns.example:8443/a%20b?ignored=x","HTTPS://DNS.EXAMPLE/custom", "https://dns.example/汉字", "tls://dns.example", "https://dns.example/a@b/%40", "https://dns.example/a%40b/@")
        for(n in 0 until 48) {
            every { DataStore.ipv6Mode } returns n%4
            every { DataStore.enableFakeDns } returns (n%2==0)
            every { DataStore.trafficSniffing } returns n%3
            every { DataStore.resolveDestination } returns (n%2==1)
            every { DataStore.bypassLanInCore } returns (n%3==1)
            every { DataStore.serviceMode } returns if(n%2==0) Key.MODE_VPN else "proxy"
            every { DataStore.remoteDns } returns dns[n%dns.size]
            every { DataStore.directDns } returns dns[(n+1)%dns.size]
            compare(selected,n%5==0,n%7==0)
        }
    }
    @Test fun chainSelectorRuleAndOverlayOrderMatchesLegacy() {
        val first=node(17,"first.example"); val second=node(2,"second.example");val third=node(33,"third.example")
        val chain=ProxyEntity(id=1,groupId=1).apply {putBean(ChainBean().applyDefaultValues().apply { proxies= mutableListOf(17,2);name="chain" })}
        val group=ProxyGroup(id=1,isSelector=true)
        val rules=listOf(
            RuleEntity(id=1,domains="full:a.example",ip="10.0.0.0/8",outbound=-1),
            RuleEntity(id=2,source="192.0.2.0/24",port="80,443,100:200",sourcePort="53,1:2",outbound=33),
            RuleEntity(id=3,domains="keyword:ads",outbound=-2),
        )
        repository(listOf(chain,first,second,third),listOf(group),rules)
        first.requireBean().customOutboundJson="""{"detour":"user-override","tag":"custom-tag"}"""
        // This tests overlay precedence; native route/DNS policy has its own core-backed matrix.
        every { DataStore.globalCustomConfig } returns """{"route":{"rules":[{"action":"reject"}],"+rules":[{"action":"sniff"}]},"dns":{"rules":[]}}"""
        chain.requireBean().customConfigJson="""{"route":{"rules+":[{"action":"resolve","strategy":"ipv4_only"}]}}"""
        compare(chain)
        group.isSelector=false;group.frontProxy=33;compare(chain)
    }
    @Test fun selectorOrderWithCollidingDatabaseIdsMatchesPlatform() {
        val nodes=(1L..40L).map {node(it*64L)}
        repository(nodes,listOf(ProxyGroup(id=1,isSelector=true)),emptyList())
        compare(nodes.first())
    }
    @Test fun immutableSnapshotDoesNotReadStoresDuringGeneration() {
        val selected=node(1,"before.example");repository(listOf(selected),emptyList(),emptyList())
        val snapshot=ConfigSnapshot.capture(selected,false,false)
        val first=snapshot.generate().result
        val before=first.config
        first.trafficMap.values.first().first().requireBean().serverAddress="mutated-output.example"
        assertEquals("before.example", snapshot.generate().result.trafficMap.values.first().first().requireBean().serverAddress)
        selected.requireBean().serverAddress="after.example"
        every { DataStore.remoteDns } throws AssertionError("settings read after capture")
        every { SagerDatabase.proxyDao } throws AssertionError("database read after capture")
        assertEquals(before,snapshot.generate().result.config)
    }
    @Test fun fullCustomPassThroughNeedsNoDatabaseOrSettings() {
        every { DataStore.remoteDns } throws AssertionError("must not read settings")
        every { SagerDatabase.proxyDao } throws AssertionError("must not read database")
        val source="  { \"outbounds\": [] }  "
        val selected=ProxyEntity(id=11).apply {putBean(ConfigBean().applyDefaultValues().apply {type=0;config=source})}
        assertEquals(source,ConfigSnapshot.capture(selected,false,false).generate().result.config)
    }
    @Test fun cyclesAndRemovedProtocolsFailClosed() {
        val chain=ProxyEntity(id=1).apply {putBean(ChainBean().applyDefaultValues().apply {proxies= mutableListOf(1)})}
        repository(listOf(chain),emptyList(),emptyList())
        assertTrue(runCatching {ConfigSnapshot.capture(chain,false,false).generate()}.isFailure)
        val hy=ProxyEntity(id=2).apply {putBean(HysteriaBean().applyDefaultValues().apply {protocolVersion=1;protocol=1})}
        repository(listOf(hy),emptyList(),emptyList())
        assertTrue(runCatching {ConfigSnapshot.capture(hy,false,false).generate()}.isFailure)
    }
}
