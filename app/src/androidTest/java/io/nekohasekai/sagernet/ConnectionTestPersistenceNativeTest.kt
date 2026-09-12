package io.nekohasekai.sagernet

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.ConnectionTestResult
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real Room/SQLite regression coverage; never opens the user's profile database. */
@RunWith(AndroidJUnit4::class)
class ConnectionTestPersistenceNativeTest {
    private fun withDatabase(test: (SagerDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), SagerDatabase::class.java
        ).build()
        try { test(db) } finally { db.close() }
    }

    private fun profile(name: String) = ProxyEntity(
        groupId = 42, userOrder = 1, tx = 10, rx = 20, error = "previous failure"
    ).apply {
        putBean(SOCKSBean().apply {
            initializeDefaultValues()
            this.name = name
            serverAddress = "127.0.0.1"
            serverPort = 1080
        })
    }

    @Test fun staleResultsPreserveLaterEditsAndTrafficAcrossBatch() = withDatabase { db ->
        val dao = db.proxyDao()
        val a = dao.addProxy(profile("A"))
        val b = dao.addProxy(profile("B"))
        val deleted = dao.addProxy(profile("deleted"))
        // URL tests capture these IDs before a separate writer edits profiles and records traffic.
        val snapshots = dao.getEntities(listOf(a, b, deleted))
        val results = snapshots.map {
            ConnectionTestResult(it.id, if (it.id == b) 2 else 1, if (it.id == b) 0 else 125,
                if (it.id == b) "connection refused" else null)
        }
        val localProfileId = dao.getById(a)!!.requireProfile().id
        val edited = dao.getById(a)!!.apply {
            requireBean().name = "renamed during test"
            requireBean().serverAddress = "edited.example"
            userOrder = 99
            groupId = 43
            sourceKey = "changed-source"
        }
        dao.updateProxy(edited)
        dao.addTraffic(a, 100, 200)
        dao.deleteById(deleted)
        dao.updateConnectionTestResults(results)

        val actual = dao.getById(a)!!
        assertEquals("renamed during test", actual.requireBean().name)
        assertEquals("edited.example", actual.requireBean().serverAddress)
        assertEquals(99L, actual.userOrder)
        assertEquals(43L, actual.groupId)
        assertEquals("changed-source", actual.sourceKey)
        assertEquals(localProfileId, actual.requireProfile().id)
        assertEquals(110L, actual.tx)
        assertEquals(220L, actual.rx)
        assertEquals(1, actual.status)
        assertEquals(125, actual.ping)
        assertNull(actual.error)
        val failed = dao.getById(b)!!
        assertEquals(2, failed.status)
        assertEquals(0, failed.ping)
        assertEquals("connection refused", failed.error)
        assertNull(dao.getById(deleted))
        dao.updateConnectionTestResults(emptyList())
        assertEquals(2, dao.getAll().size)
        assertEquals(125, dao.getById(a)!!.ping)
    }

    @Test fun batchRollsBackAllRowsWhenOneWriteFails() = withDatabase { db ->
        val dao = db.proxyDao()
        val a = dao.addProxy(profile("A"))
        val b = dao.addProxy(profile("B"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test_result BEFORE UPDATE OF status ON proxy_entities " +
                "WHEN NEW.id = $b BEGIN SELECT RAISE(ABORT, 'test batch failure'); END"
        )
        var failed = false
        try {
            dao.updateConnectionTestResults(listOf(
                ConnectionTestResult(a, 1, 42, null), ConnectionTestResult(b, 1, 43, null)
            ))
        } catch (_: android.database.SQLException) { failed = true }
        assertTrue("The SQLite trigger must reject the second update", failed)
        assertEquals(0, dao.getById(a)!!.status)
        assertEquals(0, dao.getById(a)!!.ping)
        assertEquals(0, dao.getById(b)!!.status)
    }
}
