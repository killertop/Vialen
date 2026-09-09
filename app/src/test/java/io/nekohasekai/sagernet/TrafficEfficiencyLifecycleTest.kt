package io.nekohasekai.sagernet

import io.mockk.*
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Production loop and updater, destructive-read native counter fake, actual coroutine scheduling. */
@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class TrafficEfficiencyLifecycleTest {
    private lateinit var readStats:(String,String)->Long
    private val installs=AtomicInteger()
    private lateinit var data:BaseService.Data
    private lateinit var binder:BaseService.Binder
    private lateinit var callback:ISagerNetServiceCallback
    private val consumers=ConcurrentHashMap<ISagerNetServiceCallback,Int>()
    private val persisted=CopyOnWriteArrayList<ProxyEntity>()
    private val counters=ConcurrentHashMap<String,AtomicLong>()
    private val queries=AtomicInteger()
    private val speeds=AtomicInteger()
    private var looper:TrafficLooper?=null

    @Before fun setup() {
        mockkObject(DataStore,ProfileManager)
        every { DataStore.speedInterval } returns 20
        every { DataStore.profileTrafficStatistics } returns true
        every { DataStore.showDirectSpeed } returns false
        coEvery { ProfileManager.updateProfile(any<ProxyEntity>()) } coAnswers { persisted.add(firstArg());Unit }
        readStats = { tag,direction ->
            queries.incrementAndGet()
            counters.computeIfAbsent("$tag/$direction") { AtomicLong() }.getAndSet(0)
        }
        binder=mockk(relaxed=true)
        callback=mockk(relaxed=true)
        every { binder.callbackIdMap } returns consumers
        every { callback.cbSpeedUpdate(any()) } answers { speeds.incrementAndGet();Unit }
        coEvery { binder.broadcast(any()) } coAnswers {
            firstArg<(ISagerNetServiceCallback)->Unit>().invoke(callback)
        }
        data=mockk(relaxed=true)
        every { data.binder } returns binder
        every { data.state } returns BaseService.State.Connected
    }
    @After fun cleanup() {
        runBlocking { looper?.stop() }
        unmockkAll()
    }
    private fun start(selector:Boolean=false):TrafficLooper {
        val rows=(1L..2L).map { id -> ProxyEntity(id=id,rx=id*100,tx=id*10).apply {
            putBean(SOCKSBean().applyDefaultValues())
        } }
        val config=ConfigBuildResult("{}",emptyList(),1,
            linkedMapOf("one" to listOf(rows[0]),"two" to listOf(rows[1])),
            mapOf(1L to "one",2L to "two"),if(selector) 1 else -1)
        val proxy=mockk<ProxyInstance>(relaxed=true)
        every { proxy.config } returns config
        every { data.proxy } returns proxy
        return TrafficLooper(data,readStats={tag,direction -> readStats(tag,direction)},installStats={installs.incrementAndGet();Unit}).also { looper=it;every { proxy.looper } returns it;it.start() }
    }
    private fun add(tag:String,tx:Long,rx:Long) {
        counters.computeIfAbsent("$tag/uplink") { AtomicLong() }.addAndGet(tx)
        counters.computeIfAbsent("$tag/downlink") { AtomicLong() }.addAndGet(rx)
    }
    private suspend fun awaitCondition(predicate:()->Boolean) = withTimeout(2000) {
        while(!predicate()) delay(5)
    }
    private suspend fun stop() { looper!!.stop();looper=null }

    @Test fun backgroundStatisticsAvoidConfiguredFrequencyAndStopDrainsFinalBytes() = runBlocking {
        start()
        awaitCondition { queries.get()>=6 }
        delay(40)
        val before=queries.get()
        add("one",7,11)
        delay(120) // Six configured display intervals; background timer is thirty seconds.
        assertEquals(before,queries.get())
        stop()
        val row=persisted.last { it.id==1L }
        assertEquals(17L,row.tx);assertEquals(111L,row.rx)
    }

    @Test fun noStatisticsBackgroundSleepsAndForegroundRegistrationWakesWithoutTimer() = runBlocking {
        every { DataStore.profileTrafficStatistics } returns false
        every { DataStore.speedInterval } returns 10_000
        val loop=start()
        delay(80)
        assertEquals(0,queries.get())
        consumers[callback]=SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        loop.onConsumersChanged()
        awaitCondition { speeds.get()>0 }
        assertTrue(queries.get()>=6)
        consumers.clear();loop.onConsumersChanged()
        delay(50)
        val before=queries.get();delay(80)
        assertEquals(before,queries.get())
    }

    @Test fun connectedStateWakesAlreadyRegisteredForegroundConsumer() = runBlocking {
        every { DataStore.profileTrafficStatistics } returns false
        every { DataStore.speedInterval } returns 10_000
        every { DataStore.serviceState = any() } just Runs
        data=spyk(BaseService.Data(mockk(relaxed=true)))
        every { data.binder } returns binder
        data.state=BaseService.State.Connecting
        consumers[callback]=SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        start()
        delay(80)
        assertEquals(0,queries.get())
        data.changeState(BaseService.State.Connected)
        awaitCondition { speeds.get()>0 }
        assertTrue(queries.get()>=6)
    }

    @Test fun selectionDrainsOldCounterBeforeReassigningAndRepeatedIdDoesNothing() = runBlocking {
        val loop=start(selector=true)
        awaitCondition { queries.get()>=4 }
        add(TAG_PROXY,7,11)
        loop.selectMain(2)
        awaitCondition { persisted.any { it.id==1L } }
        assertEquals(17L,persisted.last { it.id==1L }.tx)
        assertEquals(111L,persisted.last { it.id==1L }.rx)
        delay(40) // Consume selection wake before testing same-ID no-op.
        val before=queries.get();loop.selectMain(2)
        assertEquals(before,queries.get())
        add(TAG_PROXY,13,17)
        stop()
        assertEquals(33L,persisted.last { it.id==2L }.tx)
        assertEquals(217L,persisted.last { it.id==2L }.rx)
        assertEquals(17L,persisted.last { it.id==1L }.tx)
    }

    @Test fun zeroIntervalDoesNotInstallOrQueryCountersEvenOnSelectionAndStop() = runBlocking {
        every { DataStore.speedInterval } returns 0
        val loop=start(selector=true)
        consumers[callback]=SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        loop.onConsumersChanged();loop.selectMain(2)
        delay(50);stop()
        assertEquals(0,queries.get());assertTrue(persisted.isEmpty())
        assertEquals(0,installs.get())
    }

    @Test fun concurrentSelectionFinishesItsDrainBeforeStopAndCannotChangeAfterStop() = runBlocking {
        val loop=start(selector=true)
        awaitCondition { queries.get()>=4 };delay(40)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val blockOnce=AtomicInteger()
        val originalRead=readStats
        readStats = {tag,direction ->
            if(tag==TAG_PROXY && direction=="uplink" && blockOnce.getAndIncrement()==0) {
                entered.countDown()
                check(release.await(2,TimeUnit.SECONDS))
            }
            originalRead(tag,direction)
        }
        add(TAG_PROXY,19,23)
        val selection=async(Dispatchers.Default) { loop.selectMain(2) }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val stopping=async(Dispatchers.Default) { loop.stop() }
        try { delay(20) } finally { release.countDown() }
        withTimeout(2000) { selection.await();stopping.await() }
        looper=null
        assertEquals(29L,persisted.last { it.id==1L }.tx)
        assertEquals(123L,persisted.last { it.id==1L }.rx)
        val before=queries.get();loop.selectMain(1)
        assertEquals(before,queries.get())
    }
}
