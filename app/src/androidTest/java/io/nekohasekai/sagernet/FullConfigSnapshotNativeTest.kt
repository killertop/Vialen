package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullConfigSnapshotNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val benchmarkForeground = BenchmarkForegroundRule()
    @Test fun realRoomSnapshotPreservesChainAndRemainsFrozenAcrossWrites() {
        val db=SagerDatabase.instance
        val oldRemote=DataStore.remoteDns;val oldDirect=DataStore.directDns
        val oldMode=DataStore.serviceMode;val oldRouting=DataStore.enableDnsRouting
        val oldCustom=DataStore.globalCustomConfig
        val group=ProxyGroup(name="Rust-full-config-${System.nanoTime()}")
        group.id=db.groupDao().createGroup(group)
        val rules=mutableListOf<Long>()
        val originalRules=db.rulesDao().enabledRules().map { it.copy(packages=it.packages.toSet()) }
        try {
            DataStore.remoteDns="local";DataStore.directDns="local";DataStore.serviceMode=Key.MODE_VPN
            DataStore.enableDnsRouting=true;DataStore.globalCustomConfig=""
            fun node(name:String,port:Int):ProxyEntity {
                val row=ProxyEntity(groupId=group.id).apply {putBean(SOCKSBean().applyDefaultValues().apply {this.name=name;serverAddress="127.0.0.1";serverPort=port})}
                row.id=db.proxyDao().addProxy(row);return row
            }
            val a=node("first",1080);val b=node("second",1081)
            val chain=ProxyEntity(groupId=group.id).apply {putBean(ChainBean().applyDefaultValues().apply {name="chain";proxies=mutableListOf(a.id,b.id)})}
            chain.id=db.proxyDao().addProxy(chain)
            val route=RuleEntity(name="Rust DNS response test",enabled=true,domains="full:snapshot.example",ip="192.0.2.0/24",outbound=-1)
            rules.add(db.rulesDao().createRule(route))
            for (selector in listOf(false,true)) {
                group.isSelector=selector;db.groupDao().updateGroup(group)
                val snapshot=ConfigSnapshot.capture(chain,false,false)
                val fresh=snapshot.generate().result
                val actual=JsonParser.parseString(fresh.config)
                if (selector) {
                    val outbounds=actual.asJsonObject.getAsJsonArray("outbounds")
                    val chainTag="c-${chain.id}-0-${b.id}"
                    assertEquals("g-${a.id}", fresh.profileTagMap.getValue(a.id))
                    assertEquals(chainTag, fresh.profileTagMap.getValue(chain.id))
                    assertEquals("g-${a.id}", outbounds.single {
                        it.asJsonObject.get("tag")?.asString==chainTag
                    }.asJsonObject.get("detour").asString)
                }
                // This test owns the chain and snapshot boundary. The legacy oracle has
                // separate logging policy and predates native rule-set references; comparing
                // its complete config would compare different inputs on a configured phone.
                assertTrue(snapshot.ruleNames.containsKey(rules.single()))
                assertTrue(actual.asJsonObject.getAsJsonObject("route").getAsJsonArray("rules").any {
                    it.toString().contains("snapshot.example") && it.toString().contains("192.0.2.0/24")
                })
                assertEquals("proxy", actual.asJsonObject.getAsJsonObject("route")["final"].asString)
                assertEquals(if(selector) group.id else -1L, fresh.selectorGroupId)
                val chainTag=if(selector) "c-${chain.id}-0-${b.id}" else "proxy"
                val outbounds=actual.asJsonObject.getAsJsonArray("outbounds")
                val hop=outbounds.single { it.asJsonObject["tag"].asString==chainTag }.asJsonObject
                assertEquals("127.0.0.1",hop["server"].asString)
                assertEquals(1081,hop["server_port"].asInt)
                assertEquals("g-${a.id}",hop["detour"].asString)
                val front=outbounds.single { it.asJsonObject["tag"].asString=="g-${a.id}" }.asJsonObject
                assertEquals("127.0.0.1",front["server"].asString)
                assertEquals(1080,front["server_port"].asInt)
                assertEquals(setOf(a.id,b.id,chain.id),fresh.trafficMap.getValue(chainTag).map { it.id }.toSet())
                // Parse and initialize the actual packaged core; no start/network.
                Libcore.newSingBoxInstance(fresh.config,null).close()
                a.requireBean().serverAddress="after.example";db.proxyDao().updateProxy(a)
                DataStore.remoteDns="1.1.1.1"
                val regenerated=snapshot.generate().result
                assertEquals(fresh.config,regenerated.config)
                assertEquals(fresh.profileTagMap,regenerated.profileTagMap)
                assertEquals(fresh.selectorGroupId,regenerated.selectorGroupId)
                assertEquals(fresh.trafficMap.mapValues { (_,v)->v.map { it.id } },regenerated.trafficMap.mapValues { (_,v)->v.map { it.id } })
                a.requireBean().serverAddress="127.0.0.1";db.proxyDao().updateProxy(a);DataStore.remoteDns="local"
            }
        } finally {
            DataStore.remoteDns=oldRemote;DataStore.directDns=oldDirect;DataStore.serviceMode=oldMode
            DataStore.enableDnsRouting=oldRouting;DataStore.globalCustomConfig=oldCustom
            db.runInTransaction {
                rules.forEach { db.rulesDao().deleteById(it) }
                db.proxyDao().deleteByGroup(group.id);db.groupDao().deleteById(group.id)
            }
        }
        assertEquals(oldRemote,DataStore.remoteDns);assertEquals(oldDirect,DataStore.directDns)
        assertNull(db.groupDao().getById(group.id))
        assertEquals(originalRules,db.rulesDao().enabledRules())
        assertEquals(oldMode,DataStore.serviceMode);assertEquals(oldRouting,DataStore.enableDnsRouting)
        assertEquals(oldCustom,DataStore.globalCustomConfig)
    }
}
