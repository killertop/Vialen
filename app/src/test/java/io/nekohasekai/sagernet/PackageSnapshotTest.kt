package io.nekohasekai.sagernet

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.nekohasekai.sagernet.utils.PackageSnapshot
import io.nekohasekai.sagernet.utils.SnapshotLoader
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PackageSnapshotTest {
    private fun app(name: String, uid: Int) = ApplicationInfo().apply { packageName = name; this.uid = uid }
    private fun snapshot(version: Int): PackageSnapshot {
        val apps = listOf(app("one.test", version), app("two.test", version))
        return PackageSnapshot(emptyList(), apps)
    }

    @Test fun serialRefreshNeverAllowsOlderLoadToOvertakeNewerRequest() {
        val executor = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val secondRequested = CountDownLatch(1); val calls = AtomicInteger()
        val loader = SnapshotLoader {
            val version = calls.incrementAndGet()
            if (version == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            snapshot(version)
        }
        try {
            assertTrue(loader.refresh())
            val old = executor.submit<Boolean> { loader.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val newer = executor.submit<Boolean> { secondRequested.countDown(); loader.refresh() }
            assertTrue(secondRequested.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get()) // New scan cannot enter while the older scan is paused.
            repeat(100) { assertEquals(setOf(1), loader.await().packageMap.values.toSet()) }
            release.countDown()
            assertTrue(old.get(5, TimeUnit.SECONDS)); assertTrue(newer.get(5, TimeUnit.SECONDS))
            assertEquals(setOf(3), loader.await().packageMap.values.toSet())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun repeatedConcurrentQueriesSeeOneCompleteVersion() {
        val executor = Executors.newFixedThreadPool(3)
        val calls = AtomicInteger()
        val loader = SnapshotLoader { snapshot(calls.incrementAndGet()) }
        loader.refresh()
        val start = CyclicBarrier(3)
        try {
            val writer = executor.submit { start.await(); repeat(100) { assertTrue(loader.refresh()) } }
            val readers = (1..2).map { executor.submit {
                start.await()
                repeat(500) {
                    val view = loader.await()
                    assertEquals(2, view.packageMap.size)
                    val uid = view.packageMap.getValue("one.test")
                    assertEquals(uid, view.packageMap.getValue("two.test"))
                    assertEquals(setOf("one.test", "two.test"), view.uidMap[uid])
                }
            } }
            writer.get(10, TimeUnit.SECONDS); readers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    @Test fun initialFailureTerminatesWaitersAndRefreshFailureKeepsOldSnapshot() {
        var fail = true
        val loader = SnapshotLoader { if (fail) error("fixture") else snapshot(7) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<Boolean> {
                try { loader.await(); false } catch (_: IllegalStateException) { true }
            }
            assertFalse(loader.refresh())
            assertTrue(waiting.get(5, TimeUnit.SECONDS))
            fail = false; assertTrue(loader.refresh()); val old = loader.await()
            fail = true; assertFalse(loader.refresh()); assertSame(old, loader.await())
            assertNotNull(loader.failure)
        } finally { executor.shutdownNow() }
    }

    @Test fun installedRemovedReplacedAndSharedUidIndexesAreImmutable() {
        val one = app("one.test", 7); val two = app("two.test", 7)
        val packageInfo = PackageInfo().apply {
            packageName = "one.test"; applicationInfo = one
            requestedPermissions = arrayOf(Manifest.permission.INTERNET)
        }
        val source = mutableListOf(one, two)
        val before = PackageSnapshot(listOf(packageInfo), source)
        one.uid = 99; source.clear(); packageInfo.requestedPermissions!![0] = "changed"
        assertEquals(7, before.packageMap["one.test"])
        assertEquals(setOf("one.test", "two.test"), before.uidMap[7])
        assertThrows(UnsupportedOperationException::class.java) { (before.uidMap[7] as MutableSet).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (before.packageMap as MutableMap).clear() }
        before.installedPackages.getValue("one.test").applicationInfo!!.uid = 88
        assertEquals(7, before.installedPackages.getValue("one.test").applicationInfo!!.uid)
        before.application("one.test")!!.uid = 88
        assertEquals(7, before.application("one.test")!!.uid)
        val replaced = PackageSnapshot(emptyList(), listOf(app("one.test", 8), app("three.test", 9)))
        assertNull(replaced.packageMap["two.test"])
        assertEquals(setOf("one.test"), replaced.uidMap[8])
        assertEquals(setOf("three.test"), replaced.uidMap[9])
        assertEquals(setOf("one.test", "two.test"), before.uidMap[7])
    }
}
