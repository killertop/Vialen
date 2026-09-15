package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.TrafficChanges
import io.nekohasekai.sagernet.bg.proto.TrafficUpdater
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.state.ProfileListContent
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TrafficBatchTest {
    @Test fun batchQueriesUniqueActiveTagsOnceAndRetainsZeroIntervalDeltas() {
        var calls = 0; var now = 100L
        val a = TrafficUpdater.TrafficLooperData("a")
        val duplicate = TrafficUpdater.TrafficLooperData("a")
        val ignored = TrafficUpdater.TrafficLooperData("b", ignore = true)
        val updater = TrafficUpdater({ _, _ -> error("scalar JNI path must not run") }, listOf(a, duplicate, ignored), { now }) { tags ->
            calls++; assertEquals("a", tags)
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(7).putLong(11).array()
        }
        updater.updateAll()
        assertEquals(1, calls); assertEquals(7L, a.tx); assertEquals(7L, duplicate.tx)
        assertEquals(0L, a.txRate); assertEquals(0L, ignored.rx)
        now += 1000; updater.updateAll()
        assertEquals(14L, a.tx); assertEquals(7L, a.txRate)
        a.ignore = true; duplicate.ignore = true
        updater.updateAll(); assertEquals(2, calls)
    }

    @Test fun largeBatchUsesOneTransferAndNeverDoubleConsumes() {
        val items = (1..10000).map { TrafficUpdater.TrafficLooperData("tag-$it") }
        var calls = 0
        val updater = TrafficUpdater({ _, _ -> error("scalar") }, items, { 100L }) { tags ->
            assertEquals(10000, tags.split('\n').size)
            ByteBuffer.allocate(160000).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(10000) { putLong(if (calls == 0) 3 else 0); putLong(if (calls == 0) 5 else 0) }
            }.array().also { calls++ }
        }
        updater.updateAll(); updater.updateAll()
        assertEquals(2, calls); assertTrue(items.all { it.tx == 3L && it.rx == 5L })
    }

    @Test fun newSubscribersGetFullSnapshotOtherwiseOnlyChangedRowsIncludingReset() {
        val changes = TrafficChanges()
        val rows = (1L..10000L).map { TrafficData(it, 7, 11) }
        assertEquals(10000, changes.next(rows, 1).size)
        assertTrue(changes.next(rows, 1).isEmpty())
        rows[42].tx = 0; rows[42].rx = 0
        assertEquals(listOf(rows[42]), changes.next(rows, 1))
        assertEquals(10000, changes.next(rows, 2).size)
        assertEquals(40, rows.chunked(TrafficChanges.MAX_ROWS_PER_CALLBACK).size)
    }

    @Test fun contentSnapshotIgnoresTrafficButTracksHiddenConfigurationAndTestChanges() {
        val row = ProxyEntity(document = "synthetic document", status = 1, ping = 20)
        val first = ProfileListContent.capture(row)
        assertSame(row.document, first.document)
        row.tx = 999; row.rx = 123
        assertEquals(first, ProfileListContent.capture(row))
        row.document = "different synthetic document"
        assertNotEquals(first, ProfileListContent.capture(row))
        row.document = first.document; row.ping = 21
        assertNotEquals(first, ProfileListContent.capture(row))
    }
}
