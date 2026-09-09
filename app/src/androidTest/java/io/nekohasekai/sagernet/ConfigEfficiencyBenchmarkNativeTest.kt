package io.nekohasekai.sagernet

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the identical harness against baseline and candidate on one idle Android device.
 * Timed scope is production buildConfig, including Room capture, serialization, JNI and decode.
 * Setup/cleanup is excluded. No VPN is started. Logs contain no credentials or config content.
 */
@RunWith(AndroidJUnit4::class)
class ConfigEfficiencyBenchmarkNativeTest {
    @Test fun fullBuildSelectorAndSharedChains() {
        val db = SagerDatabase.instance
        val oldRemote=DataStore.remoteDns; val oldDirect=DataStore.directDns
        val oldCustom=DataStore.globalCustomConfig
        try {
            DataStore.remoteDns="local"; DataStore.directDns="local"; DataStore.globalCustomConfig=""
            for (sharedChain in listOf(false,true)) for (size in listOf(1,100,1000)) {
                val group=ProxyGroup(name="Config efficiency benchmark",isSelector=true)
                group.id=db.groupDao().createGroup(group)
                val dependencies=ProxyGroup(name="Config efficiency benchmark dependencies")
                dependencies.id=db.groupDao().createGroup(dependencies)
                try {
                    fun node(index:Int,groupId:Long):ProxyEntity {
                        val row=ProxyEntity(groupId=groupId).apply {
                            putBean(SOCKSBean().applyDefaultValues().apply {
                                name="bench-$index";serverAddress="127.0.0.1";serverPort=1080+index%100
                            })
                        }
                        row.id=db.proxyDao().addProxy(row)
                        return row
                    }
                    val nodes=mutableListOf<ProxyEntity>()
                    db.runInTransaction {
                        val hops=if(sharedChain) listOf(node(1001,dependencies.id),node(1002,dependencies.id)) else emptyList()
                        repeat(size) { index ->
                            if(sharedChain) {
                                val row=ProxyEntity(groupId=group.id).apply {
                                    putBean(ChainBean().applyDefaultValues().apply {
                                        name="bench-chain-$index";proxies=hops.map { it.id }.toMutableList()
                                    })
                                }
                                row.id=db.proxyDao().addProxy(row);nodes.add(row)
                            } else nodes.add(node(index,group.id))
                        }
                    }
                    val selected=nodes.first()
                    var expected:String?=null
                    repeat(2) { expected=buildConfig(selected).config }
                    repeat(5) { iteration ->
                        val allocBefore=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                        val gcBefore=Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                        val start=System.nanoTime()
                        val result=buildConfig(selected)
                        val elapsed=System.nanoTime()-start
                        val allocAfter=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                        val gcAfter=Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                        assertEquals(expected,result.config)
                        assertEquals(group.id,result.selectorGroupId)
                        val allocated=if(allocBefore!=null && allocAfter!=null) allocAfter-allocBefore else -1
                        val collections=if(gcBefore!=null && gcAfter!=null) gcAfter-gcBefore else -1
                        Log.i("ConfigEfficiency", "size=$size shared_chain=$sharedChain iteration=$iteration elapsed_ns=$elapsed allocated_bytes=$allocated gc_count=$collections config_bytes=${result.config.toByteArray().size}")
                    }
                } finally {
                    db.runInTransaction {
                        db.proxyDao().deleteByGroup(group.id);db.groupDao().deleteById(group.id)
                        db.proxyDao().deleteByGroup(dependencies.id);db.groupDao().deleteById(dependencies.id)
                    }
                }
            }
        } finally {
            DataStore.remoteDns=oldRemote;DataStore.directDns=oldDirect;DataStore.globalCustomConfig=oldCustom
        }
    }
}
