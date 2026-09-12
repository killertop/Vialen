package io.nekohasekai.sagernet

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.core.Profile
import kotlinx.coroutines.*
import io.nekohasekai.sagernet.group.SubscriptionDedup
import io.nekohasekai.sagernet.group.SubscriptionPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionPersistenceNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val benchmarkForeground = BenchmarkForegroundRule()
    private fun bean(name: String, host: String = name) = Profile(name = name, type = "trojan", server = host,
        port = 443, tls = Profile.Tls(), trojan = Profile.Password("synthetic"))
    private fun persist(db: SagerDatabase, group: ProxyGroup, profiles: List<Profile>) = runBlocking {
        SubscriptionPersistence.apply(db, group, profiles)
    }

    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), SagerDatabase::class.java
    ).allowMainThreadQueries().build()

    private fun withDatabase(test: (SagerDatabase) -> Unit) {
        val db = database()
        try { test(db) } finally { db.close() }
    }

    @Test fun nativeDedupThenRealRoomUpdatePreservesStateAndIsIdempotent() {
        withDatabase { db ->
            val group = ProxyGroup(id = 42, name = "before", type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION,
                subscription = io.nekohasekai.sagernet.database.SubscriptionBean().apply { initializeDefaultValues(); lastUpdated = 100 })
            db.groupDao().createGroup(group)
            fun insert(name: String, order: Long) = db.proxyDao().addProxy(
                ProxyEntity(groupId = group.id, userOrder = order).apply {
                    sourceKey = SubscriptionPersistence.sourceKey(bean(name))
                    putProfile(bean(name))
                }
            )
            val a = insert("A", 8)
            val b = insert("B", 3)
            insert("removed", 9)
            val prepared = SubscriptionDedup.apply(listOf(bean("A", "changed"), bean("B"), bean("C"), bean("duplicate", "C")))
            group.name = "after"
            group.subscription!!.lastUpdated = 200
            val result = persist(db, group, prepared.proxies)
            val rows = db.proxyDao().getByGroup(42)
            assertEquals(listOf("A", "B", "C"), rows.map { it.displayName() })
            assertEquals(listOf(1L, 2L, 3L), rows.map { it.userOrder })
            assertEquals(a, rows[0].id); assertEquals(b, rows[1].id)
            assertEquals("changed", rows[0].requireProfile().server)
            assertEquals("after", db.groupDao().getById(42)!!.name)
            assertEquals(200, db.groupDao().getById(42)!!.subscription!!.lastUpdated)
            assertEquals(listOf("C"), result.added)
            assertEquals(mapOf("A" to "A"), result.updated)
            assertEquals(listOf("removed"), result.deleted)
            val repeat = persist(db, group, prepared.proxies)
            assertEquals(0, repeat.changed)
            assertEquals(rows.map { it.id }, db.proxyDao().getByGroup(42).map { it.id })
        }
    }

    @Test fun failedInsertRollsBackEarlierInsertAndRetainsExistingRows() {
        withDatabase { db ->
            val group = ProxyGroup(id = 42, name = "before")
            db.groupDao().createGroup(group)
            persist(db, group, listOf(bean("old")))
            val original = db.proxyDao().getByGroup(42).single().id
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_second BEFORE INSERT ON proxy_entities WHEN NEW.userOrder = 2 BEGIN SELECT RAISE(ABORT, 'test failure'); END")
            assertThrows(Exception::class.java) {
                persist(db, group, listOf(bean("new1"), bean("new2")))
            }
            val rows = db.proxyDao().getByGroup(42)
            assertEquals(listOf("old"), rows.map { it.displayName() })
            assertEquals(original, rows.single().id)
        }
    }

    @Test fun failedGroupWriteRollsBackAllProxyChanges() {
        withDatabase { db ->
            val group = ProxyGroup(id = 42, name = "before")
            db.groupDao().createGroup(group)
            persist(db, group, listOf(bean("A"), bean("old")))
            val ids = db.proxyDao().getByGroup(42).map { it.id }
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_group BEFORE UPDATE ON proxy_groups BEGIN SELECT RAISE(ABORT, 'group failure'); END")
            group.name = "after"
            assertThrows(Exception::class.java) {
                persist(db, group, listOf(bean("A", "changed"), bean("new")))
            }
            val rows = db.proxyDao().getByGroup(42)
            assertEquals(ids, rows.map { it.id })
            assertEquals(listOf("A", "old"), rows.map { it.requireProfile().server })
            assertEquals("before", db.groupDao().getById(42)!!.name)
        }
    }

    @Test fun duplicateNamesKeepFirstIdAndEmptyRefreshCannotClearGroup() {
        withDatabase { db ->
            val group = ProxyGroup(id = 42, name = "group")
            db.groupDao().createGroup(group)
            db.groupDao().createGroup(ProxyGroup(id = 43, name = "other"))
            fun row(groupId: Long, order: Long) = db.proxyDao().addProxy(
                ProxyEntity(groupId = groupId, userOrder = order).apply { sourceKey = SubscriptionPersistence.sourceKey(bean("A")); putProfile(bean("A")) }
            )
            val first = row(42, 1)
            row(42, 2)
            val unrelated = row(43, 1)
            persist(db, group, listOf(bean("A")))
            assertEquals(first, db.proxyDao().getByGroup(42).single().id)
            assertThrows(IllegalArgumentException::class.java) { persist(db, group, emptyList()) }
            assertEquals(first, db.proxyDao().getByGroup(42).single().id)
            assertEquals(unrelated, db.proxyDao().getByGroup(43).single().id)
        }
    }

    @Test fun cancelledRefreshDoesNotWriteRoom() {
        withDatabase { db ->
            val group = ProxyGroup(id = 42, name = "before")
            db.groupDao().createGroup(group)
            persist(db, group, listOf(bean("old")))
            val before = db.proxyDao().getByGroup(42).single()
            runBlocking {
                val job = launch {
                    currentCoroutineContext().cancel()
                    SubscriptionPersistence.apply(db, group, listOf(bean("replacement")))
                }
                job.join()
                assertTrue(job.isCancelled)
            }
            val after = db.proxyDao().getByGroup(42).single()
            assertEquals(before.id, after.id)
            assertEquals(before.requireProfile(), after.requireProfile())
        }
    }

    @Test fun deletedGroupCannotLeaveOrphanProfiles() {
        withDatabase { db ->
            val deletedGroup = ProxyGroup(id = 42, name = "deleted while fetching")
            assertThrows(IllegalStateException::class.java) {
                persist(db, deletedGroup, listOf(bean("orphan")))
            }
            assertTrue(db.proxyDao().getAll().isEmpty())
            assertNull(db.groupDao().getById(42))
        }
    }
}
