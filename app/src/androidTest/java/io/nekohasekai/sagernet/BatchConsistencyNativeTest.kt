package io.nekohasekai.sagernet

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.utils.PackageSnapshot
import io.nekohasekai.sagernet.utils.SnapshotLoader
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Isolated debug package only. No Android VPN/service, external network or production data. */
@RunWith(AndroidJUnit4::class)
class BatchConsistencyNativeTest {
    @get:Rule val profileState = ProfileSelectionStateRule()
    private class Service : BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "BatchConsistencyFixture"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null
        override fun createNotification(profileName: String): ServiceNotification = error("not used")
        override fun acquireWakeLock() = Unit
    }

    @Test fun orderOnlyWritesAndRollbackUseActualDeviceSQLite() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SagerDatabase::class.java).build()
        try {
            val dao = db.proxyDao()
            val ids = (1..4).map { dao.addProxy(ProxyEntity(groupId = 42, userOrder = it * 10L, tx = 100, rx = 200, document = "old")) }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE order_writes (id INTEGER NOT NULL)")
            sql.execSQL("CREATE TRIGGER count_order AFTER UPDATE OF userOrder ON proxy_entities BEGIN INSERT INTO order_writes VALUES (NEW.id); END")
            sql.execSQL("CREATE TRIGGER interleave BEFORE UPDATE OF userOrder ON proxy_entities BEGIN UPDATE proxy_entities SET document = 'new', tx = 300, rx = 0, status = 1, ping = 42, error = NULL WHERE id = OLD.id; END")
            dao.deleteProfiles(ids.take(2).map { ProfileDeletion(it, 42) })
            dao.getByGroup(42).forEach {
                assertEquals("new", it.document); assertEquals(300L, it.tx); assertEquals(0L, it.rx)
                assertEquals(1, it.status); assertEquals(42, it.ping)
            }
            sql.query("SELECT COUNT(*), COUNT(DISTINCT id) FROM order_writes").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertEquals(2, it.getInt(1))
            }
            sql.execSQL("CREATE TRIGGER reject_sort BEFORE UPDATE OF userOrder ON proxy_entities BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            assertThrows(android.database.SQLException::class.java) { dao.deleteProfiles(listOf(ProfileDeletion(ids[2], 42))) }
            assertEquals(ids.drop(2), dao.getIdsByGroup(42))
        } finally { db.close() }
    }

    @Test fun packageSnapshotsPublishWholeImmutableVersionsOnDevice() {
        var version = 7
        var fail = false
        val loader = SnapshotLoader {
            check(!fail) { "fixture" }
            PackageSnapshot(emptyList(), listOf("one.test", "two.test").map { name ->
                android.content.pm.ApplicationInfo().apply { packageName = name; uid = version }
            })
        }
        assertTrue(loader.refresh()); val old = loader.await()
        version = 8; assertTrue(loader.refresh()); val current = loader.await()
        assertEquals(setOf("one.test", "two.test"), current.uidMap[8])
        assertEquals(7, old.packageMap["one.test"])
        fail = true; assertFalse(loader.refresh()); assertSame(current, loader.await())
        assertThrows(UnsupportedOperationException::class.java) { (current.uidMap[8] as MutableSet).clear() }
        val failed = SnapshotLoader<PackageSnapshot> { error("fixture") }
        assertFalse(failed.refresh())
        assertThrows(IllegalStateException::class.java) { failed.await() }
    }

    @Test fun binderResetKeepsMainResponsiveAndFinalFlushPreservesNewBytes() {
        check(BuildConfig.APPLICATION_ID.endsWith(".debug"))
        val db = SagerDatabase.instance // The isolated test package database, never the release package.
        val group = ProxyGroup(name = "batch-consistency-fixture").apply { id = db.groupDao().createGroup(this) }
        val executor = Executors.newFixedThreadPool(3)
        val release = CountDownLatch(1)
        val initialSample = CountDownLatch(1)
        val resetSample = CountDownLatch(1)
        val observeReset = AtomicBoolean(false)
        val counters = ConcurrentHashMap<String, AtomicLong>()
        val service = Service()
        var loop: TrafficLooper? = null
        val oldStatistics = DataStore.profileTrafficStatistics
        try {
            DataStore.profileTrafficStatistics = true
            val rows = (1..2).map { ProxyEntity(groupId = group.id, tx = 100, rx = 200).apply {
                putBean(SOCKSBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 1080 })
                id = db.proxyDao().addProxy(this)
            } }
            val proxy = ProxyInstance(rows[0])
            proxy.config = ConfigBuildResult("{}", emptyList(), rows[0].id,
                linkedMapOf("one" to listOf(rows[0]), "two" to listOf(rows[1])),
                mapOf(rows[0].id to "one", rows[1].id to "two"), group.id)
            service.data.proxy = proxy; service.data.state = BaseService.State.Connected
            val traffic = TrafficLooper(service.data, readStats = { tag, direction ->
                val bytes = counters.computeIfAbsent("$tag/$direction") { AtomicLong() }.getAndSet(0)
                if (tag == "two" && direction == "downlink") {
                    initialSample.countDown()
                    if (observeReset.compareAndSet(true, false)) resetSample.countDown()
                }
                bytes
            }, installStats = {})
            loop = traffic; proxy.looper = traffic; traffic.start()
            assertTrue(initialSample.await(5, TimeUnit.SECONDS))
            fun add(tx: Long, rx: Long) {
                counters.computeIfAbsent("$TAG_PROXY/uplink") { AtomicLong() }.addAndGet(tx)
                counters.computeIfAbsent("$TAG_PROXY/downlink") { AtomicLong() }.addAndGet(rx)
            }
            val dbBlocked = CountDownLatch(1)
            val blocker = executor.submit { db.runInTransaction { dbBlocked.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            assertTrue(dbBlocked.await(5, TimeUnit.SECONDS))
            add(7, 11); observeReset.set(true)
            val clearing = executor.submit<Boolean> { service.data.binder.clearTraffic(group.id) }
            assertTrue(resetSample.await(5, TimeUnit.SECONDS))
            val mainDone = CountDownLatch(1)
            add(3, 5)
            Handler(Looper.getMainLooper()).post { traffic.selectMain(rows[1].id); mainDone.countDown() }
            try { assertTrue("Main must complete selection while SQLite is blocked", mainDone.await(3, TimeUnit.SECONDS)) }
            finally { release.countDown() }
            add(13, 17)
            blocker.get(5, TimeUnit.SECONDS)
            assertTrue(clearing.get(5, TimeUnit.SECONDS))
            runBlocking { traffic.stop() }; loop = null
            assertEquals(3L, db.proxyDao().getById(rows[0].id)!!.tx)
            assertEquals(5L, db.proxyDao().getById(rows[0].id)!!.rx)
            assertEquals(13L, db.proxyDao().getById(rows[1].id)!!.tx)
            assertEquals(17L, db.proxyDao().getById(rows[1].id)!!.rx)
            assertFalse(runBlocking { traffic.clearTraffic(group.id) })
        } finally {
            release.countDown()
            runBlocking { loop?.stop() }
            service.data.binder.close()
            executor.shutdown(); check(executor.awaitTermination(10, TimeUnit.SECONDS))
            db.proxyDao().deleteByGroup(group.id); db.groupDao().deleteById(group.id)
            DataStore.profileTrafficStatistics = oldStatistics
        }
    }
}
