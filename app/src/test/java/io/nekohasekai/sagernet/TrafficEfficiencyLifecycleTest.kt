package io.nekohasekai.sagernet

import android.os.SystemClock
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.aidl.TrafficData
import io.mockk.*
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import moe.matsuri.nb4a.TempDatabase
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.Logs
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
@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class TrafficEfficiencyLifecycleTest {
    private lateinit var database: SagerDatabase
    private lateinit var listener: ProfileManager.Listener
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
        // DataStore's static initializer captures BOTH DAOs. Install them before
        // touching DataStore, as in ProfileAutoSelectionConcurrencyTest, so this
        // fixture cannot initialize the app's real Room singleton or require SagerNet.application.
        val preferences=mockk<KeyValuePair.Dao>(relaxed=true)
        every { preferences.get(any()) } returns null
        mockkObject(PublicDatabase.Companion,TempDatabase.Companion)
        every { PublicDatabase.kvPairDao } returns preferences
        every { TempDatabase.profileCacheDao } returns preferences
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        mockkObject(SagerDatabase.Companion)
        every { SagerDatabase.instance } returns database
        every { SagerDatabase.proxyDao } returns database.proxyDao()
        listener = mockk(relaxed = true)
        coEvery { listener.onUpdated(any<TrafficData>()) } coAnswers {
            database.proxyDao().getById(firstArg<TrafficData>().id)?.let { persisted.add(it) }
            Unit
        }
        ProfileManager.addListener(listener)
        mockkObject(DataStore)
        every { DataStore.speedInterval } returns 20
        every { DataStore.profileTrafficStatistics } returns true
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
        try {
            // Join actual sampler/writer work while its DAO/ProfileManager mocks still exist.
            runBlocking { looper?.stop() }
        } finally {
            looper=null
            ProfileManager.removeListener(listener)
            database.close()
            unmockkAll()
        }
    }
    private fun start(
        selector:Boolean=false,
        supplied:ConfigBuildResult?=null,
        checkpointIntervalMillis:Long=TrafficLooper.CHECKPOINT_INTERVAL_MILLIS,
        elapsedRealtime:()->Long=SystemClock::elapsedRealtime,
    ):TrafficLooper {
        val rows=(1L..2L).map { id -> ProxyEntity(id=id,rx=id*100,tx=id*10).apply {
            putBean(SOCKSBean().applyDefaultValues())
        } }
        val config=supplied ?: ConfigBuildResult("{}",emptyList(),1,
            linkedMapOf("one" to listOf(rows[0]),"two" to listOf(rows[1])),
            mapOf(1L to "one",2L to "two"),if(selector) 1 else -1)
        database.proxyDao().insert(config.trafficMap.values.flatten().distinctBy { it.id })
        val proxy=mockk<ProxyInstance>(relaxed=true)
        every { proxy.config } returns config
        // changeState reads its backing field directly; populate it for the real Data spy too.
        data.proxy = proxy
        every { data.proxy } returns proxy
        return TrafficLooper(
            data,
            readStats={tag,direction -> readStats(tag,direction)},
            installStats={installs.incrementAndGet();Unit},
            checkpointIntervalMillis=checkpointIntervalMillis,
            elapsedRealtime=elapsedRealtime,
        ).also { looper=it;every { proxy.looper } returns it;it.start() }
    }
    private fun add(tag:String,tx:Long,rx:Long) {
        counters.computeIfAbsent("$tag/uplink") { AtomicLong() }.addAndGet(tx)
        counters.computeIfAbsent("$tag/downlink") { AtomicLong() }.addAndGet(rx)
    }
    private suspend fun awaitCondition(predicate:()->Boolean) = withTimeout(2000) {
        while(!predicate()) delay(5)
    }
    private suspend fun stop() { looper!!.stop();looper=null }

    @Test fun backgroundStatisticsUseInternalPolicyAndStopDrainsFinalBytes() = runBlocking {
        start()
        awaitCondition { queries.get()>=6 }
        delay(40)
        val before=queries.get()
        add("one",7,11)
        delay(120) // Legacy 20 ms preference is ignored; background timer is thirty seconds.
        assertEquals(before,queries.get())
        stop()
        val row=persisted.last { it.id==1L }
        assertEquals(17L,row.tx);assertEquals(111L,row.rx)
    }

    @Test fun periodicCheckpointPersistsEveryTrackedRowWithoutSelectionOrStop() = runBlocking {
        start(
            checkpointIntervalMillis = 40,
            // Robolectric's SystemClock does not advance with coroutine delays.
            elapsedRealtime = { System.nanoTime() / 1_000_000L },
        )
        awaitCondition { queries.get() >= 6 }
        add("one", 7, 11)
        add("two", 13, 17)
        awaitCondition {
            database.proxyDao().getById(1)!!.tx == 17L &&
                database.proxyDao().getById(1)!!.rx == 111L &&
                database.proxyDao().getById(2)!!.tx == 33L &&
                database.proxyDao().getById(2)!!.rx == 217L
        }
        // Later checkpoints re-read reset native counters but must not add the same bytes twice.
        delay(120)
        assertEquals(17L, database.proxyDao().getById(1)!!.tx)
        assertEquals(111L, database.proxyDao().getById(1)!!.rx)
        assertEquals(33L, database.proxyDao().getById(2)!!.tx)
        assertEquals(217L, database.proxyDao().getById(2)!!.rx)
    }

    @Test fun resetDrainsOldCountersAndQueuedWritesCannotRestoreThem() = runBlocking {
        val loop = start(selector = true)
        add(TAG_PROXY, 7, 11)
        loop.selectMain(2) // Queue old-selection persistence before resetting the group.
        add(TAG_PROXY, 13, 17)
        assertTrue(loop.clearTraffic(0))
        assertEquals(0L, database.proxyDao().getById(1)!!.tx)
        assertEquals(0L, database.proxyDao().getById(2)!!.rx)
        add(TAG_PROXY, 3, 5)
        stop()
        assertEquals(0L, database.proxyDao().getById(1)!!.tx)
        assertEquals(0L, database.proxyDao().getById(1)!!.rx)
        assertEquals(3L, database.proxyDao().getById(2)!!.tx)
        assertEquals(5L, database.proxyDao().getById(2)!!.rx)
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
        val ownedBinder=data.binder
        every { data.binder } returns binder
        data.state=BaseService.State.Connecting
        consumers[callback]=SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        try {
            start()
            delay(80)
            assertEquals(0,queries.get())
            data.changeState(BaseService.State.Connected)
            awaitCondition { speeds.get()>0 }
            assertTrue(queries.get()>=6)
        } finally { ownedBinder.close() }
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

    @Test fun legacyZeroIntervalStillInstallsSelectsAndFlushesStatistics() = runBlocking {
        every { DataStore.speedInterval } returns 0
        val loop=start(selector=true)
        assertTrue(loop.isSelected(1))
        add(TAG_PROXY,7,11)
        loop.selectMain(2)
        assertTrue(loop.isSelected(2))
        add(TAG_PROXY,13,17)
        consumers[callback]=SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        loop.onConsumersChanged()
        awaitCondition { speeds.get()>0 }
        stop()
        assertEquals(1,installs.get())
        assertEquals(17L,persisted.last { it.id==1L }.tx)
        assertEquals(111L,persisted.last { it.id==1L }.rx)
        assertEquals(33L,persisted.last { it.id==2L }.tx)
        assertEquals(217L,persisted.last { it.id==2L }.rx)
        verify(exactly=0) { DataStore.speedInterval }
    }

    @Test fun tileAndBackgroundActivityAreNotRealtimeConsumers() = runBlocking {
        every { DataStore.profileTrafficStatistics } returns false
        consumers[callback]=SagerConnection.CONNECTION_ID_TILE
        val loop=start()
        delay(80)
        assertEquals(0,queries.get())
        consumers[callback]=SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND
        loop.onConsumersChanged()
        delay(80)
        assertEquals(0,queries.get())
        assertEquals(0,speeds.get())
        assertEquals(1,installs.get())
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
    @Test fun savingStaleProfilePreservesCommittedTraffic() = runBlocking {
        start(selector = true)
        val draft = checkNotNull(database.proxyDao().getById(2))
        draft.requireBean().name = "saved draft"
        ProfileManager.addTraffic(TrafficData(2, 17, 29))
        ProfileManager.updateProfile(draft)
        val saved = checkNotNull(database.proxyDao().getById(2))
        assertEquals(37L, saved.tx)
        assertEquals(229L, saved.rx)
        assertEquals("saved draft", saved.requireBean().name)
    }

    private fun editSecondProfile() {
        val row = checkNotNull(database.proxyDao().getById(2))
        row.userOrder = 91
        row.requireBean().apply { name = "edited while connected"; serverAddress = "new.example.com" }
        database.proxyDao().updateProxy(row)
    }

    private fun assertSecondProfileEditSurvives() {
        val row = checkNotNull(database.proxyDao().getById(2))
        assertEquals(91L, row.userOrder)
        assertEquals("edited while connected", row.requireBean().name)
        assertEquals("new.example.com", row.requireBean().serverAddress)
        coVerify(exactly = 0) { listener.onUpdated(any<ProxyEntity>(), any()) }
    }

    @Test fun neverSelectedProfileEditSurvivesStopWithoutTraffic() = runBlocking {
        start(selector = true)
        editSecondProfile()
        stop()
        assertSecondProfileEditSurvives()
        assertEquals(20L, database.proxyDao().getById(2)!!.tx)
        assertEquals(200L, database.proxyDao().getById(2)!!.rx)
    }

    @Test fun selectorWritesOnlyTrafficAfterOtherProfileWasEdited() = runBlocking {
        val loop = start(selector = true)
        editSecondProfile()
        add(TAG_PROXY, 7, 11)
        loop.selectMain(2)
        awaitCondition { persisted.any { it.id == 1L } }
        add(TAG_PROXY, 13, 17)
        loop.selectMain(1)
        awaitCondition { persisted.any { it.id == 2L } }
        assertSecondProfileEditSurvives()
        add(TAG_PROXY, 3, 5)
        stop()
        assertSecondProfileEditSurvives()
        assertEquals(20L, database.proxyDao().getById(1)!!.tx)
        assertEquals(116L, database.proxyDao().getById(1)!!.rx)
        assertEquals(33L, database.proxyDao().getById(2)!!.tx)
        assertEquals(217L, database.proxyDao().getById(2)!!.rx)
    }

    @Test fun disabledStatisticsNeverWritesProfilesEvenWithFinalBytes() = runBlocking {
        every { DataStore.profileTrafficStatistics } returns false
        val loop = start(selector = true)
        editSecondProfile()
        add(TAG_PROXY, 7, 11)
        loop.selectMain(2)
        add(TAG_PROXY, 13, 17)
        stop()
        assertSecondProfileEditSurvives()
        assertTrue(persisted.isEmpty())
        assertEquals(20L, database.proxyDao().getById(2)!!.tx)
    }

    @Test fun clearDoesNotRestoreHistoricalOrAlreadyFlushedBytes() = runBlocking {
        val loop = start(selector = true)
        add(TAG_PROXY, 7, 11)
        loop.selectMain(2)
        awaitCondition { persisted.any { it.id == 1L } }
        editSecondProfile()
        ProfileManager.clearTraffic(0)
        assertEquals(0L, database.proxyDao().getById(1)!!.tx)
        // Bytes still buffered by the running core are new pending session increments.
        add(TAG_PROXY, 13, 17)
        stop()
        assertSecondProfileEditSurvives()
        assertEquals(0L, database.proxyDao().getById(1)!!.tx)
        assertEquals(0L, database.proxyDao().getById(1)!!.rx)
        assertEquals(13L, database.proxyDao().getById(2)!!.tx)
        assertEquals(17L, database.proxyDao().getById(2)!!.rx)
    }

    @Test fun stoppedClearReportsOnlyRowsClearedByItsTransaction() = runBlocking {
        start()
        stop()
        val insertedAfterCommit = mockk<ProfileManager.Listener>(relaxed = true)
        coEvery { insertedAfterCommit.onUpdated(any<TrafficData>()) } coAnswers {
            if (database.proxyDao().getById(99L) == null) {
                database.proxyDao().insert(listOf(ProxyEntity(id = 99L, tx = 77L).apply {
                    putBean(SOCKSBean().applyDefaultValues())
                }))
            }
            Unit
        }
        ProfileManager.addListener(insertedAfterCommit)
        try {
            val clearedIds = ProfileManager.clearTraffic(0)
            assertEquals(setOf(1L, 2L), clearedIds.toSet())
            assertEquals(0L, database.proxyDao().getById(1L)!!.tx)
            assertEquals(0L, database.proxyDao().getById(2L)!!.rx)
            assertEquals(77L, database.proxyDao().getById(99L)!!.tx)
        } finally {
            ProfileManager.removeListener(insertedAfterCommit)
        }
    }

    @Test fun listenerFailureCannotDuplicateCommittedBytesOrAbortQueuedWrites() = runBlocking {
        // Android Go JNI is unavailable on the host; record the diagnostic boundary only.
        mockkObject(Logs)
        every { Logs.w(any<Throwable>()) } just Runs
        val failures = AtomicInteger()
        val broken = mockk<ProfileManager.Listener>(relaxed = true)
        coEvery { broken.onUpdated(any<TrafficData>()) } coAnswers {
            failures.incrementAndGet()
            throw IllegalStateException("Deliberately broken traffic listener")
        }
        // Put the failing listener first to also prove later listeners still receive updates.
        ProfileManager.removeListener(listener)
        ProfileManager.addListener(broken)
        ProfileManager.addListener(listener)
        try {
            val loop = start(selector = true)
            add(TAG_PROXY, 7, 11)
            loop.selectMain(2)
            awaitCondition { persisted.any { it.id == 1L } }
            add(TAG_PROXY, 13, 17)
            loop.selectMain(1)
            awaitCondition { persisted.any { it.id == 2L } }
            add(TAG_PROXY, 3, 5)
            stop()
            assertEquals(3, failures.get())
            verify(exactly = 3) { Logs.w(any<Throwable>()) }
            assertEquals(20L, database.proxyDao().getById(1)!!.tx)
            assertEquals(116L, database.proxyDao().getById(1)!!.rx)
            assertEquals(33L, database.proxyDao().getById(2)!!.tx)
            assertEquals(217L, database.proxyDao().getById(2)!!.rx)
        } finally {
            ProfileManager.removeListener(broken)
        }
    }

    private fun counterRow(id: Long) = ProxyEntity(id = id).apply { putBean(SOCKSBean().applyDefaultValues()) }

    @Test fun sharedNodeAggregatesTwoReferenceTagsWithoutDuplicateOwnership() = runBlocking {
        val shared = counterRow(1)
        val first = counterRow(10)
        val second = counterRow(20)
        val reads = ConcurrentHashMap<String, AtomicInteger>()
        val nativeRead = readStats
        readStats = { tag, direction -> reads.computeIfAbsent("$tag/$direction") { AtomicInteger() }.incrementAndGet(); nativeRead(tag, direction) }
        start(supplied = ConfigBuildResult("{}", emptyList(), 10,
            linkedMapOf("chain-a" to listOf(shared, shared, first), "chain-b" to listOf(shared, second)),
            mapOf(10L to "chain-a", 20L to "chain-b"), 1))
        awaitCondition { queries.get() >= 8 }
        delay(30)
        val before = reads.mapValues { it.value.get() }
        add("chain-a", 7, 11)
        add("chain-b", 13, 17)
        stop()
        assertEquals(20L, database.proxyDao().getById(1)!!.tx)
        assertEquals(28L, database.proxyDao().getById(1)!!.rx)
        assertEquals(7L, database.proxyDao().getById(10)!!.tx)
        assertEquals(13L, database.proxyDao().getById(20)!!.tx)
        for (tag in listOf("chain-a", "chain-b")) for (direction in listOf("uplink", "downlink")) {
            val key = "$tag/$direction"
            assertEquals("Native resetting counter must be read once", 1, reads.getValue(key).get() - before.getValue(key))
        }
    }

    @Test fun selectorChainMembersAndUnselectedRuleExitAllAccumulate() = runBlocking {
        val shared = counterRow(1)
        val first = counterRow(10)
        val second = counterRow(20)
        val rule = counterRow(30)
        val loop = start(supplied = ConfigBuildResult("{}", emptyList(), 10,
            linkedMapOf("chain-a" to listOf(shared, first), "chain-b" to listOf(shared, second), "rule-exit" to listOf(rule)),
            mapOf(10L to "chain-a", 20L to "chain-b"), 1))
        add(TAG_PROXY, 7, 11)
        add("rule-exit", 5, 9)
        loop.selectMain(20)
        awaitCondition { database.proxyDao().getById(10)!!.tx == 7L }
        add(TAG_PROXY, 13, 17)
        add("chain-a", 2, 3) // A route still explicitly targets the former candidate.
        stop()
        assertEquals(22L, database.proxyDao().getById(1)!!.tx)
        assertEquals(31L, database.proxyDao().getById(1)!!.rx)
        assertEquals(9L, database.proxyDao().getById(10)!!.tx)
        assertEquals(13L, database.proxyDao().getById(20)!!.tx)
        assertEquals(5L, database.proxyDao().getById(30)!!.tx)
        assertEquals(9L, database.proxyDao().getById(30)!!.rx)
    }


    @Test fun clearReleasesStatisticsLockDuringDatabaseWorkAndCountsNewBytesOnce() = runBlocking {
        val loop = start(selector = true)
        val dao = database.proxyDao()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val threads = java.util.concurrent.Executors.newSingleThreadExecutor()
        val guarded = object : ProxyEntity.Dao by dao {
            override fun clearTraffic(ids: List<Long>) {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                dao.clearTraffic(ids)
            }
        }
        every { SagerDatabase.proxyDao } returns guarded
        add(TAG_PROXY, 7, 11)
        val clearing = async(Dispatchers.IO) { loop.clearTraffic(0) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // A selection callback must finish while the DB is deliberately blocked.
            add(TAG_PROXY, 3, 5)
            threads.submit { loop.selectMain(2) }.get(2, TimeUnit.SECONDS)
            add(TAG_PROXY, 13, 17)
        } finally { release.countDown(); threads.shutdownNow() }
        assertTrue(clearing.await())
        stop()
        assertEquals(3L, dao.getById(1)!!.tx); assertEquals(5L, dao.getById(1)!!.rx)
        assertEquals(13L, dao.getById(2)!!.tx); assertEquals(17L, dao.getById(2)!!.rx)
    }

    @Test fun binderClearFailureIsFalseAndLeavesPendingBytesForFinalFlush() = runBlocking {
        // Host JVM cannot initialize the Android Go logger; keep the real DB exception.
        mockkObject(Logs)
        every { Logs.w(any<String>(), any<Throwable>()) } just Runs
        start(selector = true)
        val actualBinder = BaseService.Binder(data)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_clear BEFORE UPDATE OF tx ON proxy_entities WHEN NEW.tx = 0 BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        add(TAG_PROXY, 7, 11)
        try {
            assertFalse(withContext(Dispatchers.IO) { actualBinder.clearTraffic(0) })
            assertEquals(10L, database.proxyDao().getById(1)!!.tx)
            sql.execSQL("DROP TRIGGER reject_clear")
            stop()
            assertEquals(17L, database.proxyDao().getById(1)!!.tx)
            assertEquals(111L, database.proxyDao().getById(1)!!.rx)
        } finally { actualBinder.close() }
    }

    @Test fun stopWaitsForAcceptedResetAndOldInstanceCannotClearReplacement() = runBlocking {
        val loop = start(selector = true)
        val dao = database.proxyDao()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        every { SagerDatabase.proxyDao } returns object : ProxyEntity.Dao by dao {
            override fun clearTraffic(ids: List<Long>) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); dao.clearTraffic(ids)
            }
        }
        add(TAG_PROXY, 7, 11)
        val clearing = async(Dispatchers.IO) { loop.clearTraffic(0) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        add(TAG_PROXY, 3, 5)
        val stopping = async(Dispatchers.IO) { loop.stop() }
        release.countDown()
        withTimeout(5000) { assertTrue(clearing.await()); stopping.await() }
        looper = null
        assertEquals(3L, dao.getById(1)!!.tx); assertEquals(5L, dao.getById(1)!!.rx)
        every { data.proxy } returns mockk(relaxed = true)
        assertFalse(loop.clearTraffic(0))
        assertEquals(3L, dao.getById(1)!!.tx)
    }

    @Test fun repeatedBinderResetsAndStoppedResetReportActualCommit() = runBlocking {
        start()
        val actualBinder = BaseService.Binder(data)
        val dao = database.proxyDao()
        val calls = AtomicInteger()
        every { SagerDatabase.proxyDao } returns object : ProxyEntity.Dao by dao {
            override fun clearTraffic(ids: List<Long>) {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                calls.incrementAndGet(); dao.clearTraffic(ids)
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { actualBinder.clearTraffic(0) })
            assertTrue(withContext(Dispatchers.IO) { actualBinder.clearTraffic(0) })
            stop()
            every { data.state } returns BaseService.State.Stopped
            assertTrue(withContext(Dispatchers.IO) { actualBinder.clearTraffic(0) })
            assertEquals(3, calls.get())
            assertEquals(0L, dao.getById(1)!!.tx)
            // A synchronous in-process main-thread misuse is rejected, not queued as success.
            assertFalse(actualBinder.clearTraffic(0))
            assertEquals(3, calls.get())
        } finally { actualBinder.close() }
    }

    @Test fun cancellationAfterResetAcceptanceJoinsCommitAndDoesNotLoseNewTraffic() = runBlocking {
        val loop = start(selector = true)
        val dao = database.proxyDao()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        every { SagerDatabase.proxyDao } returns object : ProxyEntity.Dao by dao {
            override fun clearTraffic(ids: List<Long>) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); dao.clearTraffic(ids)
            }
        }
        add(TAG_PROXY, 7, 11)
        val clearing = async(Dispatchers.IO) { loop.clearTraffic(0) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            clearing.cancel()
            add(TAG_PROXY, 3, 5)
        } finally { release.countDown() }
        clearing.join() // The accepted non-cancellable section has fully finished.
        assertTrue(clearing.isCancelled)
        assertEquals(0L, dao.getById(1)!!.tx)
        stop()
        assertEquals(3L, dao.getById(1)!!.tx); assertEquals(5L, dao.getById(1)!!.rx)
    }

    @Test fun queuedBinderRequestRejectsChangedLifecycleBeforeTouchingDatabase() = runBlocking {
        start()
        val actualBinder = BaseService.Binder(data)
        val gate = BaseService.trafficOperations
        val versionRead = CountDownLatch(1)
        val reads = AtomicInteger()
        every { data.stateVersion } answers { versionRead.countDown(); reads.get().toLong() }
        gate.lock()
        val clearing = async(Dispatchers.IO) { actualBinder.clearTraffic(0) }
        try {
            assertTrue(versionRead.await(5, TimeUnit.SECONDS))
            reads.incrementAndGet()
        } finally { gate.unlock() }
        try {
            assertFalse(clearing.await())
            assertEquals(10L, database.proxyDao().getById(1)!!.tx)
        } finally { actualBinder.close() }
    }

    @Test fun concurrentBinderResetsAreSerializedAndNewBytesAreAddedOnce() = runBlocking {
        start(selector = true)
        val actualBinder = BaseService.Binder(data)
        val dao = database.proxyDao()
        val firstEntered = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        every { SagerDatabase.proxyDao } returns object : ProxyEntity.Dao by dao {
            override fun clearTraffic(ids: List<Long>) {
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                dao.clearTraffic(ids)
            }
        }
        val first = async(Dispatchers.IO) { actualBinder.clearTraffic(0) }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { actualBinder.clearTraffic(0) }
        release.countDown()
        try {
            assertTrue(first.await()); assertTrue(second.await()); assertEquals(2, calls.get())
            add(TAG_PROXY, 3, 5)
            stop()
            assertEquals(3L, dao.getById(1)!!.tx); assertEquals(5L, dao.getById(1)!!.rx)
        } finally { actualBinder.close() }
    }
}
