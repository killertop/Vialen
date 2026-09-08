package io.nekohasekai.sagernet

import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.group.SubscriptionDedup
import io.nekohasekai.sagernet.group.SubscriptionPersistence
import io.nekohasekai.sagernet.oracle.FrozenV15SubscriptionDedup
import io.nekohasekai.sagernet.oracle.FrozenV15SubscriptionPersistence
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real, isolated Room databases; frozen 297a077 native implementations are the oracle. */
@RunWith(AndroidJUnit4::class)
class KotlinSubscriptionUpdateNativeTest {
    private fun bean(name: String?, host: String? = name): TrojanBean = TrojanBean().apply {
        initializeDefaultValues(); this.name = name; serverAddress = host; serverPort = 443
    }
    private fun config(name: String, value: String) = ConfigBean().apply {
        initializeDefaultValues(); this.name = name; type = 1; config = value
    }
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), SagerDatabase::class.java
    ).allowMainThreadQueries().build()

    private data class Report(val changed: Int, val added: List<String>,
        val updated: List<Pair<String, String>>, val deleted: List<String>)
    private fun apply(db: SagerDatabase, nodes: List<AbstractBean>, native: Boolean): Report {
        val group = db.groupDao().getById(42)!!
        group.name = "refreshed"; group.subscription!!.lastUpdated = 200
        return if (native) FrozenV15SubscriptionPersistence.apply(db, group, nodes).let {
            Report(it.changed, it.added, it.updated.entries.map { e -> e.key to e.value }, it.deleted)
        } else SubscriptionPersistence.apply(db, group, nodes).let {
            Report(it.changed, it.added, it.updated.entries.map { e -> e.key to e.value }, it.deleted)
        }
    }

    // Compare every persisted column, including all Bean blobs, counters and group selection fields.
    private fun snapshot(db: SagerDatabase, table: String): List<List<Any?>> =
        db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { c ->
            val rows = mutableListOf<List<Any?>>(c.columnNames.toList())
            while (c.moveToNext()) rows.add((0 until c.columnCount).map { i ->
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                    else -> c.getString(i)
                }
            })
            rows
        }
    private fun seed(db: SagerDatabase, nodes: List<AbstractBean>) {
        db.groupDao().createGroup(ProxyGroup(id = 42, name = "original", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply { initializeDefaultValues(); lastUpdated = 100 },
            isSelector = true, frontProxy = 100, landingProxy = 101))
        db.groupDao().createGroup(ProxyGroup(id = 43, name = "unrelated"))
        nodes.forEachIndexed { i, b ->
            b.customOutboundJson = "{\"localOutbound\":$i}"
            b.customConfigJson = "{\"localConfig\":$i}"
            db.proxyDao().addProxy(ProxyEntity(id = 100L + i, groupId = 42, userOrder = i + 1L,
                tx = 123L + i, rx = 456L + i, status = 2, ping = 27 + i,
                uuid = "uuid-$i", error = "error-$i").apply { putBean(b) })
        }
        db.proxyDao().addProxy(ProxyEntity(id = 900, groupId = 43, userOrder = 1).apply { putBean(bean("other")) })
    }
    private fun paired(old: () -> List<AbstractBean>, fresh: () -> List<AbstractBean>,
                       check: (SagerDatabase, Report) -> Unit) {
        val kotlin = database()
        try {
            val rust = database()
            try {
                seed(kotlin, old()); seed(rust, old())
                val actual = apply(kotlin, fresh(), false)
                val expected = apply(rust, fresh(), true)
                assertEquals(expected, actual)
                for (table in listOf("proxy_entities", "proxy_groups"))
                    assertEquals(table, snapshot(rust, table), snapshot(kotlin, table))
                check(kotlin, actual)
                // A second refresh must match too, including stable IDs and zero changes.
                val repeat = apply(kotlin, fresh(), false)
                assertEquals(apply(rust, fresh(), true), repeat)
                assertEquals(0, repeat.changed)
                assertEquals(snapshot(rust, "proxy_entities"), snapshot(kotlin, "proxy_entities"))
            } finally { rust.close() }
        } finally { kotlin.close() }
    }

    @Test fun crossClassReplacementRetainsIdCountersAndLocalOverrides() = paired(
        { listOf(config("A", "{\"type\":\"socks\"}"), bean("B")) },
        { listOf(bean("A", "new.example"), config("B", "{\"type\":\"http\"}")) }
    ) { db, report ->
        assertEquals(2, report.changed)
        val rows = db.proxyDao().getByGroup(42)
        assertEquals(listOf(100L, 101L), rows.map { it.id })
        assertEquals(listOf(123L, 124L), rows.map { it.tx })
        assertEquals(listOf(456L, 457L), rows.map { it.rx })
        rows.forEachIndexed { i, r ->
            assertEquals(2, r.status); assertEquals(27 + i, r.ping)
            assertEquals("uuid-$i", r.uuid); assertEquals("error-$i", r.error)
            assertEquals("{\"localOutbound\":$i}", r.requireBean().customOutboundJson)
            assertEquals("{\"localConfig\":$i}", r.requireBean().customConfigJson)
        }
        val group = db.groupDao().getById(42)!!
        assertTrue(group.isSelector); assertEquals(100L, group.frontProxy); assertEquals(101L, group.landingProxy)
    }

    @Test fun orderOnlyRefreshPreservesIdsAndReportsNoContentChange() = paired(
        { listOf(bean("A"), bean("B")) }, { listOf(bean("B"), bean("A")) }
    ) { db, report ->
        assertEquals(Report(0, emptyList(), emptyList(), emptyList()), report)
        assertEquals(listOf(101L, 100L), db.proxyDao().getByGroup(42).map { it.id })
        assertEquals(listOf(1L, 2L), db.proxyDao().getByGroup(42).map { it.userOrder })
    }

    @Test fun duplicateOldNamesKeepLastRowAndRemoveOnlyUnretainedIds() = paired(
        { listOf(bean("A", "old1"), bean("A", "old2"), bean("deleted")) },
        { listOf(bean("A", "changed"), bean("added")) }
    ) { db, report ->
        assertEquals(101L, db.proxyDao().getByGroup(42).first().id)
        assertEquals(listOf("A", "deleted"), report.deleted)
        assertEquals(listOf("added"), report.added)
        assertEquals(listOf("A" to "A"), report.updated)
        assertEquals(4, report.changed)
        assertEquals(900L, db.proxyDao().getByGroup(43).single().id)
    }

    @Test fun unicodeAndNullNamesUseSameDisplayNameMatching() = paired(
        { listOf(bean("中文🔥", "例子.test"), bean(null, "fallback.example")) },
        { listOf(bean(null, "fallback.example"), bean("中文🔥", "新.test")) }
    ) { db, _ -> assertEquals(listOf(101L, 100L), db.proxyDao().getByGroup(42).map { it.id }) }

    @Test fun dedupNullUnicodeAndFramingRemainDistinctWithoutBeanHashing() {
        fun input(): List<AbstractBean> = listOf(
            bean("null endpoint", null), bean("literal null", "null"),
            bean("unicode🔥", "例子.test"), bean("duplicate unicode", "例子.test"),
            bean("framing", "a:443"), bean("different framing", "a").apply { serverPort = 443443 },
            config("config one", "endpoint:Trojan:例子.test:443"),
            config("config duplicate", "endpoint:Trojan:例子.test:443"),
            config("different config", "endpoint:Trojan:例子.test:443:")
        )
        val expected = FrozenV15SubscriptionDedup.apply(input())
        val actual = SubscriptionDedup.apply(input())
        assertEquals(expected.duplicates, actual.duplicates)
        assertEquals(expected.proxies.size, actual.proxies.size)
        expected.proxies.zip(actual.proxies).forEach { (a, b) ->
            assertEquals(a.javaClass, b.javaClass)
            assertArrayEquals(KryoConverters.serialize(a), KryoConverters.serialize(b))
        }
        assertEquals(7, actual.proxies.size)
        assertTrue(actual.proxies.any { it.serverAddress == null })
        assertTrue(actual.proxies.any { it.serverAddress == "null" })
    }
}
