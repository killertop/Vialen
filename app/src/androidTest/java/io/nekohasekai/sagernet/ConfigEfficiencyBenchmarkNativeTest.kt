package io.nekohasekai.sagernet

import android.os.Debug
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import com.google.gson.JsonObject
import moe.matsuri.nb4a.utils.JavaUtil.gson
import java.io.File
import java.security.MessageDigest
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
    @get:org.junit.Rule val state = org.junit.rules.RuleChain.outerRule(ProfileSelectionStateRule())
        .around(BenchmarkForegroundRule())
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun rssKb(): Long = File("/proc/self/status").useLines { rows ->
        rows.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1
    }
    @Test fun fullBuildSelectorAndSharedChains() {
        val db = SagerDatabase.instance
        val oldRemote=DataStore.remoteDns; val oldDirect=DataStore.directDns
        val customDao=PublicDatabase.instance.keyValuePairDao()
        val oldCustom=customDao[Key.GLOBAL_CUSTOM_CONFIG]?.let { row -> KeyValuePair(row.key).also { it.valueType=row.valueType;it.value=row.value.copyOf() } }
        val args=InstrumentationRegistry.getArguments()
        val warmups=args.getString("configBenchWarmups")?.toInt() ?: 2
        val repeats=args.getString("configBenchRepeats")?.toInt() ?: 5
        require(warmups>0 && repeats>0)
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
                    repeat(warmups) { expected=buildConfig(selected).config }
                    repeat(repeats) { iteration ->
                        val allocBefore=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                        val gcBefore=Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                        val nativeBefore=Debug.getNativeHeapAllocatedSize()
                        val rssBefore=rssKb()
                        val cpuBefore=Process.getElapsedCpuTime()
                        val threadBefore=Debug.threadCpuTimeNanos()
                        val start=System.nanoTime()
                        val result=buildConfig(selected)
                        val elapsed=System.nanoTime()-start
                        val threadCpu=Debug.threadCpuTimeNanos()-threadBefore
                        val cpu=Process.getElapsedCpuTime()-cpuBefore
                        val rssAfter=rssKb()
                        val nativeAfter=Debug.getNativeHeapAllocatedSize()
                        val allocAfter=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                        val gcAfter=Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
                        assertEquals(expected,result.config)
                        assertEquals(group.id,result.selectorGroupId)
                        val allocated=if(allocBefore!=null && allocAfter!=null) allocAfter-allocBefore else -1
                        val collections=if(gcBefore!=null && gcAfter!=null) gcAfter-gcBefore else -1
                        val semantic=JsonObject().apply {
                            addProperty("config",result.config)
                            addProperty("main",result.mainEntId)
                            addProperty("selector_group",result.selectorGroupId)
                            add("tags",gson.toJsonTree(result.profileTagMap.entries.map { listOf(it.key,it.value) }))
                            add("traffic",gson.toJsonTree(result.trafficMap.entries.map { e -> listOf(e.key,e.value.map { it.id }) }))
                        }
                        val report=JsonObject().apply {
                            addProperty("schema",1);addProperty("size",size);addProperty("shared_chain",sharedChain)
                            addProperty("iteration",iteration);addProperty("elapsed_ns",elapsed)
                            addProperty("allocated_bytes",allocated);addProperty("gc_count",collections)
                            addProperty("process_cpu_ms",cpu);addProperty("thread_cpu_ns",threadCpu)
                            addProperty("rss_before_kb",rssBefore);addProperty("rss_after_kb",rssAfter)
                            addProperty("native_before_bytes",nativeBefore);addProperty("native_after_bytes",nativeAfter)
                            addProperty("config_bytes",result.config.toByteArray().size)
                            addProperty("semantic_sha256",hash(semantic.toString()))
                        }
                        Log.i("ConfigEfficiency",report.toString())
                    }
                } finally {
                    db.runInTransaction {
                        db.proxyDao().deleteByGroup(group.id);db.groupDao().deleteById(group.id)
                        db.proxyDao().deleteByGroup(dependencies.id);db.groupDao().deleteById(dependencies.id)
                    }
                }
            }
        } finally {
            DataStore.remoteDns=oldRemote;DataStore.directDns=oldDirect
            if(oldCustom==null) customDao.delete(Key.GLOBAL_CUSTOM_CONFIG) else customDao.put(oldCustom)
        }
    }
}
