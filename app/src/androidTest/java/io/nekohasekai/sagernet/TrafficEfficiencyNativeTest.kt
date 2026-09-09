package io.nekohasekai.sagernet

import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Actual production sampler/Binder/Room and Go counters, through a real loopback SOCKS exchange.
 * Uses outbound-only config and no main-instance registration, Android service, VPN or external host.
 */
@RunWith(AndroidJUnit4::class)
class TrafficEfficiencyNativeTest {
    @get:Rule val profileState=ProfileSelectionStateRule()
    private class FakeService:BaseService.Interface {
        override val data=BaseService.Data(this)
        override val tag="TrafficEfficiencyNative"
        override var wakeLock:PowerManager.WakeLock?=null
        override var upstreamInterfaceName:String?=null
        override fun createNotification(profileName:String):ServiceNotification=error("No notification expected")
        override fun acquireWakeLock()=Unit
    }
    private class Callback:ISagerNetServiceCallback.Stub() {
        val speeds=AtomicInteger()
        val traffic=ConcurrentHashMap<Long,TrafficData>()
        override fun stateChanged(state:Int,profileName:String?,msg:String?)=Unit
        override fun cbSpeedUpdate(stats:SpeedDisplayData?) { speeds.incrementAndGet() }
        override fun cbTrafficUpdate(stats:TrafficData?) { if(stats!=null) traffic[stats.id]=stats.copy() }
        override fun cbSelectorUpdate(id:Long)=Unit
    }
    private suspend fun awaitCondition(predicate:()->Boolean)=withTimeout(3000) {
        while(!predicate()) delay(10)
    }

    @Test fun realStatsStayIdleInBackgroundWakeAndDrainOnCoreClose()=runBlocking {
        val db=SagerDatabase.instance
        val preferences=PublicDatabase.instance
        val dao=preferences.keyValuePairDao()
        val extraKeys=listOf(Key.SPEED_INTERVAL,Key.PROFILE_TRAFFIC_STATISTICS,Key.SHOW_DIRECT_SPEED)
        val before=linkedMapOf<String,KeyValuePair?>()
        preferences.runInTransaction {
            extraKeys.forEach { key -> before[key]=dao[key]?.let { row ->
                KeyValuePair(row.key).also { it.valueType=row.valueType;it.value=row.value.copyOf() }
            } }
        }
        val priorState=DataStore.serviceState
        val group=ProxyGroup(name="traffic-efficiency-${System.nanoTime()}")
        group.id=db.groupDao().createGroup(group)
        val service=FakeService()
        val callback=Callback()
        var instance:ProxyInstance?=null
        profileState.preservingFailure({
            DataStore.serviceMode=Key.MODE_PROXY
            DataStore.directDns="local";DataStore.remoteDns="local"
            DataStore.speedInterval=40;DataStore.profileTrafficStatistics=true;DataStore.showDirectSpeed=false
            val nonce="traffic-${System.nanoTime()}"
            LoopbackSocksFixture(nonce).use { fixture ->
                val row=ProxyEntity(groupId=group.id,tx=11,rx=37).apply {
                    putBean(SOCKSBean().applyDefaultValues().apply {
                        name=nonce;serverAddress="127.0.0.1";serverPort=fixture.port
                    })
                    id=db.proxyDao().addProxy(this)
                }
                val proxy=ProxyInstance(row,service)
                instance=proxy
                proxy.config=buildConfig(row,forTest=true)
                proxy.box=Libcore.newSingBoxInstance(proxy.config.config,null)
                proxy.box.start()
                service.data.proxy=proxy
                service.data.state=BaseService.State.Connecting
                val queries=AtomicInteger()
                // Count calls without replacing values: every read is the actual JNI resetting counter.
                proxy.looper=TrafficLooper(service.data,readStats={tag,direction ->
                    queries.incrementAndGet();proxy.box.queryStats(tag,direction)
                })
                proxy.looper!!.start()
                awaitCondition { queries.get()>=4 }
                delay(80)
                val idleQueries=queries.get()
                val idleStart=System.nanoTime();val idleCpu=Process.getElapsedCpuTime()
                delay(240) // Six configured foreground periods.
                assertEquals("Background must not use configured 40 ms timer",idleQueries,queries.get())
                val backgroundCpu=Process.getElapsedCpuTime()-idleCpu
                val backgroundMs=(System.nanoTime()-idleStart)/1_000_000
                service.data.binder.registerCallback(callback,SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
                delay(80)
                assertEquals("Connecting must not send foreground speed",0,callback.speeds.get())
                val wakeStart=System.nanoTime()
                service.data.changeState(BaseService.State.Connected)
                awaitCondition { callback.speeds.get()>0 }
                val wakeMs=(System.nanoTime()-wakeStart)/1_000_000
                assertTrue("Connected failed to wake background sampler promptly",wakeMs<1000)
                service.data.binder.unregisterCallback(callback)
                delay(100)
                val previousSpeeds=callback.speeds.get()
                val registrationStart=System.nanoTime()
                service.data.binder.registerCallback(callback,SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
                awaitCondition { callback.speeds.get()>previousSpeeds }
                val registrationMs=(System.nanoTime()-registrationStart)/1_000_000
                assertTrue("Foreground registration did not wake promptly",registrationMs<1000)
                service.data.binder.unregisterCallback(callback)
                delay(100)
                // Register a background consumer to observe final persistence notification only.
                service.data.binder.registerCallback(callback,SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
                delay(100)
                val beforeRequest=queries.get()
                val latency=withContext(Dispatchers.Default) {
                    Libcore.urlTest(proxy.box,"http://198.18.0.254/$nonce",4000)
                }
                assertTrue("Real SOCKS request failed",latency>=0)
                assertEquals("Real HTTP RTT sends two requests",2,fixture.requests.get())
                awaitCondition { fixture.diagnosticSnapshot().first().contains("active=0 ") }
                assertEquals("Background request must remain pending in Go counters",beforeRequest,queries.get())
                val beforeClose=db.proxyDao().getById(row.id)!!
                assertEquals(11L,beforeClose.tx);assertEquals(37L,beforeClose.rx)
                // Production close orders actual core close before sampler final drain and Room writes.
                proxy.close();instance=null
                val final=db.proxyDao().getById(row.id)!!
                assertTrue("Final uplink bytes were lost",final.tx>11)
                assertTrue("Final downlink bytes were lost",final.rx>37)
                val delivered=checkNotNull(callback.traffic[row.id])
                assertEquals(final.tx,delivered.tx);assertEquals(final.rx,delivered.rx)
                assertTrue("Stop must query final native counters",queries.get()>beforeRequest)
                Log.i("TrafficEfficiency", "native=true requests=${fixture.requests.get()} configured_ms=40 background_ms=$backgroundMs idle_query_delta=0 idle_cpu_ms=$backgroundCpu wake_ms=$wakeMs registration_wake_ms=$registrationMs final_tx_delta=${final.tx-11} final_rx_delta=${final.rx-37} pid=${Process.myPid()}")
            }
        },{
            profileState.cleanupSteps({ instance?.close();instance=null },{
                service.data.binder.close();service.data.proxy=null;DataStore.serviceState=priorState
            },{
                db.runInTransaction { db.proxyDao().deleteByGroup(group.id);db.groupDao().deleteById(group.id) }
                assertNull(db.groupDao().getById(group.id))
            },{
                preferences.runInTransaction {
                    before.forEach { (key,row) -> if(row==null) dao.delete(key) else dao.put(row) }
                }
                before.forEach { (key,row) ->
                    val actual=dao[key]
                    assertTrue("Raw preference restore failed: $key",if(row==null) actual==null else actual!=null &&
                        actual.valueType==row.valueType && actual.value.contentEquals(row.value))
                }
            })
        })
    }
}
