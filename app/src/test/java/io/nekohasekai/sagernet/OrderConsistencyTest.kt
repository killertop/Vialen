package io.nekohasekai.sagernet

import androidx.room.Room
import io.mockk.*
import io.nekohasekai.sagernet.database.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OrderConsistencyTest {
    private lateinit var db: SagerDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        mockkObject(SagerDatabase.Companion)
        every { SagerDatabase.instance } returns db
        every { SagerDatabase.proxyDao } returns db.proxyDao()
    }
    @After fun close() { unmockkObject(SagerDatabase.Companion); db.close() }

    @Test fun reorderDoesNotOverwriteInterleavedOwnedFields() {
        val id = db.proxyDao().addProxy(ProxyEntity(groupId = 42, userOrder = 9, document = "old", tx = 100, rx = 200))
        // Deterministic SQLite interleaving immediately before the outer sorting write.
        // A whole-row UPDATE overwrites these values; an order-only UPDATE preserves them.
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER interleave BEFORE UPDATE OF userOrder ON proxy_entities
            BEGIN UPDATE proxy_entities SET document = 'new', tx = 300, rx = 0,
            status = 1, ping = 42, error = NULL WHERE id = OLD.id; END""")
        GroupManager.rearrange(42)
        val row = db.proxyDao().getById(id)!!
        assertEquals("new", row.document)
        assertEquals(300L, row.tx); assertEquals(0L, row.rx)
        assertEquals(1, row.status); assertEquals(42, row.ping); assertNull(row.error)
        assertEquals(1L, row.userOrder)
    }

    @Test fun batchReordersEachAffectedGroupOnceAndUsesCurrentGuards() {
        val dao = db.proxyDao()
        val ids = (1..8).map { dao.addProxy(ProxyEntity(groupId = if (it <= 4) 42 else 43, userOrder = it * 10L)) }
        db.openHelper.writableDatabase.execSQL("CREATE TABLE order_writes (id INTEGER NOT NULL)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER count_orders AFTER UPDATE OF userOrder ON proxy_entities BEGIN INSERT INTO order_writes VALUES (NEW.id); END")
        val removed = dao.deleteProfiles(listOf(ProfileDeletion(ids[0], 42), ProfileDeletion(ids[1], 42),
            ProfileDeletion(ids[4], 43), ProfileDeletion(ids[4], 43), ProfileDeletion(ids[7], 42)))
        assertEquals(setOf(ids[0], ids[1], ids[4]), removed.map { it.id }.toSet())
        assertEquals(listOf(1L, 2L), dao.getByGroup(42).map { it.userOrder })
        assertEquals(listOf(1L, 2L, 3L), dao.getByGroup(43).map { it.userOrder })
        db.openHelper.readableDatabase.query("SELECT COUNT(*), COUNT(DISTINCT id) FROM order_writes").use {
            assertTrue(it.moveToFirst()); assertEquals(5, it.getInt(0)); assertEquals(5, it.getInt(1))
        }
        // A node moved to a different group after confirmation is not deleted.
        assertNotNull(dao.getById(ids[7]))
        assertTrue(dao.deleteProfiles(emptyList()).isEmpty()) // Undo before commit produces no delete.
    }

    @Test fun deleteAndSortFailuresRollBackEntireBatch() {
        val dao = db.proxyDao()
        val ids = (1..4).map { dao.addProxy(ProxyEntity(groupId = 42, userOrder = it * 10L)) }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON proxy_entities WHEN OLD.id = ${ids[1]} BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertThrows(android.database.SQLException::class.java) {
            dao.deleteProfiles(ids.take(2).map { ProfileDeletion(it, 42) })
        }
        assertEquals(ids, dao.getIdsByGroup(42))
        sql.execSQL("DROP TRIGGER reject_delete")
        sql.execSQL("CREATE TRIGGER reject_sort BEFORE UPDATE OF userOrder ON proxy_entities WHEN OLD.id = ${ids[3]} BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertThrows(android.database.SQLException::class.java) {
            dao.deleteProfiles(listOf(ProfileDeletion(ids[0], 42)))
        }
        assertEquals(ids, dao.getIdsByGroup(42))
        assertEquals(listOf(10L, 20L, 30L, 40L), dao.getByGroup(42).map { it.userOrder })
    }

    @Test fun unavailableDeletionRechecksDocumentAndResultAtCommit() {
        val dao = db.proxyDao()
        val id = dao.addProxy(ProxyEntity(groupId = 42, document = "edited", status = 2))
        assertTrue(dao.deleteProfiles(listOf(ProfileDeletion(id, 42, "old"))).isEmpty())
        dao.updateConnectionTestResult(id, 1, 42, null)
        assertTrue(dao.deleteProfiles(listOf(ProfileDeletion(id, 42, "edited"))).isEmpty())
        dao.updateConnectionTestResult(id, 2, 0, "fixture")
        assertEquals(1, dao.deleteProfiles(listOf(ProfileDeletion(id, 42, "edited"))).size)
    }

    @Test fun dragOrdersRollBackTogetherAndNeverReorderAnotherGroup() {
        val dao = db.proxyDao()
        val a = dao.addProxy(ProxyEntity(groupId = 42, userOrder = 10))
        val b = dao.addProxy(ProxyEntity(groupId = 42, userOrder = 20))
        val moved = dao.addProxy(ProxyEntity(groupId = 43, userOrder = 99))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_drag BEFORE UPDATE OF userOrder ON proxy_entities WHEN OLD.id = $b BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertThrows(android.database.SQLException::class.java) { dao.updateOrders(42, linkedMapOf(a to 20L, b to 10L)) }
        assertEquals(10L, dao.getById(a)!!.userOrder); assertEquals(20L, dao.getById(b)!!.userOrder)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_drag")
        dao.updateOrders(42, linkedMapOf(a to 20L, b to 10L, moved to 30L))
        assertEquals(listOf(b, a), dao.getIdsByGroup(42))
        assertEquals(99L, dao.getById(moved)!!.userOrder)
    }
}
