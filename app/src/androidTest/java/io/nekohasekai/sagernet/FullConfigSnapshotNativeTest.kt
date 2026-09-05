package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.oracle.buildLegacyConfig
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullConfigSnapshotNativeTest {
    private fun semantic(value: JsonElement, key: String = ""): JsonElement {
        if (value.isJsonObject) return JsonObject().apply { value.asJsonObject.entrySet().forEach { (k,v) -> add(k,semantic(v,k)) } }
        if (value.isJsonArray) {
            val children=value.asJsonArray.map { semantic(it) }
            return JsonArray().apply {
                (if (key in setOf("domain","domain_suffix","domain_keyword","domain_regex","user_id")) children.sortedBy { it.toString() } else children).forEach(::add)
            }
        }
        return value
    }
    @Test fun realRoomSnapshotMatchesLegacyAndRemainsFrozenAcrossWrites() {
        val db=SagerDatabase.instance
        val oldRemote=DataStore.remoteDns;val oldDirect=DataStore.directDns
        val oldMode=DataStore.serviceMode;val oldRouting=DataStore.enableDnsRouting
        val oldCustom=DataStore.globalCustomConfig
        val group=ProxyGroup(name="Rust-full-config-${System.nanoTime()}")
        group.id=db.groupDao().createGroup(group)
        val rules=mutableListOf<Long>()
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
            val route=RuleEntity(name="Rust DNS response test",domains="full:snapshot.example",ip="192.0.2.0/24",outbound=-1)
            rules.add(db.rulesDao().createRule(route))
            for (selector in listOf(false,true)) {
                group.isSelector=selector;db.groupDao().updateGroup(group)
                val old=buildLegacyConfig(chain)
                val snapshot=ConfigSnapshot.capture(chain,false,false)
                val fresh=snapshot.generate().result
                assertEquals(semantic(JsonParser.parseString(old.config)),semantic(JsonParser.parseString(fresh.config)))
                assertEquals(old.profileTagMap,fresh.profileTagMap)
        assertEquals(old.profileTagMap.keys.toList(),fresh.profileTagMap.keys.toList())
        assertEquals(old.trafficMap.keys.toList(),fresh.trafficMap.keys.toList())
                assertEquals(old.selectorGroupId,fresh.selectorGroupId)
                assertEquals(old.trafficMap.mapValues { (_,v)->v.map { it.id } },fresh.trafficMap.mapValues { (_,v)->v.map { it.id } })
                // Parse and initialize the actual packaged core; no start/network.
                Libcore.newSingBoxInstance(fresh.config,null).close()
                a.requireBean().serverAddress="after.example";db.proxyDao().updateProxy(a)
                DataStore.remoteDns="1.1.1.1"
                assertEquals(fresh.config,snapshot.generate().result.config)
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
    }
}
