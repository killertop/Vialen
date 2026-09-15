package io.nekohasekai.sagernet

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.proto.TrafficChanges
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic rows and outbound-only native instance in the isolated package. */
@RunWith(AndroidJUnit4::class)
class TrafficBatchNativeTest {
    @get:Rule val profileState = ProfileSelectionStateRule()

    @Test fun batchJniDrainsFinalCountersOnceAndUsesBoundedParcel() {
        val box = Libcore.newSingBoxInstance("""{"outbounds":[{"type":"direct","tag":"proxy"}]}""", null)
        try {
            box.setV2rayStats("proxy")
            box.start()
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5000
                val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val response = worker.submit {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                            socket.getOutputStream().flush()
                        }
                    }
                    Libcore.urlTest(box, "http://127.0.0.1:${server.localPort}/fixture", 3000)
                    response.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    box.close()
                    val bytes = box.queryStatsBatch("proxy\nmissing\nproxy")
                    assertEquals(48, bytes.size)
                    val values = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val tx = values.long; val rx = values.long
                    assertTrue(tx > 0); assertTrue(rx > 0)
                    assertEquals(0L, values.long); assertEquals(0L, values.long)
                    assertEquals(tx, values.long); assertEquals(rx, values.long)
                    assertTrue(box.queryStatsBatch("proxy").all { it == 0.toByte() })
                } finally { worker.shutdownNow() }
            }
            val parcel = Parcel.obtain()
            try {
                parcel.writeTypedList((1L..TrafficChanges.MAX_ROWS_PER_CALLBACK.toLong()).map { TrafficData(it, Long.MAX_VALUE, Long.MAX_VALUE) })
                assertTrue("batch must remain comfortably below Binder limit", parcel.dataSize() < 16 * 1024)
            } finally { parcel.recycle() }
        } finally { box.close() }
    }

    @Test fun bulkImportPublishesOneCommittedBatchAndPreservesManualSelection() = runBlocking {
        check(BuildConfig.APPLICATION_ID.endsWith(".debug"))
        val db = SagerDatabase.instance
        val group = ProxyGroup(name = "bulk-import-fixture").apply { id = db.groupDao().createGroup(this) }
        var batches = 0
        val listener = object : ProfileManager.Listener {
            override suspend fun onAdd(profile: ProxyEntity) = error("unexpected single event")
            override suspend fun onAdded(profiles: List<ProxyEntity>) {
                if (profiles.firstOrNull()?.groupId != group.id) return
                batches++
                assertEquals(1000, profiles.size)
                assertEquals(1000, db.proxyDao().getIdsByGroup(group.id).size)
                // A user selection made during post-commit notification must survive.
                DataStore.selectedProxy = profiles.last().id
            }
            override suspend fun onUpdated(data: TrafficData) = Unit
            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit
            override suspend fun onRemoved(groupId: Long, profileId: Long) = Unit
        }
        ProfileManager.addListener(listener)
        try {
            val rows = ProfileManager.createProfilesForImport(group.id, (1..1000).map {
                Profile(name = "synthetic-$it", type = "socks", server = "127.0.0.1", port = 1080, socks = Profile.Socks())
            })
            assertEquals(1, batches)
            assertEquals(rows.last().id, DataStore.selectedProxy)
        } finally {
            ProfileManager.removeListener(listener)
            db.proxyDao().deleteByGroup(group.id)
            db.groupDao().deleteById(group.id)
        }
    }
}
