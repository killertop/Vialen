package io.nekohasekai.sagernet

import androidx.room.Room
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import io.nekohasekai.sagernet.bg.proto.UnsupportedStandaloneProbe
import moe.matsuri.nb4a.Protocols
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LegacyManagementRegressionTest {
    private fun node(p: Profile) = ProxyEntity(groupId = 42).putProfile(p)
    private val base = Profile(type = "vless", server = "example.test", port = 443,
        vless = Profile.Vless(uuid = "00000000-0000-4000-8000-000000000001"))

    @Test fun manualDedupKeepsEveryConnectionDifferenceAndSkipsSpecialTypes() {
        fun key(p: Profile) = Protocols.deduplicationKey(node(p))
        assertEquals(key(base), key(base.copy(id = "other", name = "renamed")))
        for (changed in listOf(base.copy(vless = Profile.Vless(uuid = "00000000-0000-4000-8000-000000000002")),
            base.copy(tls = Profile.Tls(serverName = "cover.test")),
            base.copy(transport = Profile.Transport(type = "ws", path = "/other")))) assertNotEquals(key(base), key(changed))
        val password = Profile(type = "trojan", server = "example.test", port = 443, trojan = Profile.Password("one"))
        assertNotEquals(key(password), key(password.copy(trojan = Profile.Password("two"))))
        assertNotEquals(key(base.copy(server = "node.example1", port = 2345)), key(base.copy(server = "node.example12", port = 345)))
        for (document in listOf(ProfileDocument(kind = "chain", hops = listOf(1, 2)),
            ProfileDocument(kind = "raw_config", content = "{}"))) {
            assertNull(Protocols.deduplicationKey(ProxyEntity(type = document.entityType(), document = ProfileDocument.encode(document))))
        }
    }

    @Test fun staleTestAndClearCannotUndoCommittedConfigurationOrTraffic() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.proxyDao()
            val id = dao.addProxy(node(base).apply { tx = 100; rx = 200; status = 1; ping = 30 })
            val snapshot = dao.getById(id)!!
            val edited = dao.getById(id)!!.apply { putProfile(base.copy(server = "updated.test")) }
            dao.updateProxy(edited)
            dao.addTraffic(id, 200, 300)
            dao.updateConnectionTestResults(listOf(ConnectionTestResult(id, 2, 999, "stale", snapshot.document)))
            val current = dao.getById(id)!!
            assertEquals("updated.test", current.requireProfile().server)
            assertEquals(300L, current.tx); assertEquals(500L, current.rx)
            assertEquals(30, current.ping)
            dao.clearConnectionTestResults(42)
            dao.addTraffic(id, 50, 60)
            val cleared = dao.getById(id)!!
            assertEquals("updated.test", cleared.requireProfile().server)
            assertEquals(350L, cleared.tx); assertEquals(560L, cleared.rx)
            assertEquals(0, cleared.status); assertEquals(0, cleared.ping); assertNull(cleared.error)
            dao.updateConnectionTestResults(listOf(ConnectionTestResult(id, 1, 42, null, cleared.document)))
            assertEquals(42, dao.getById(id)!!.ping)
            assertEquals(350L, dao.getById(id)!!.tx)
        } finally { db.close() }
    }

    @Test fun confirmationCannotDeleteChangedCandidateOrLastRemainingCopy() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.proxyDao()
            val keeper = dao.addProxy(node(base))
            val duplicate = dao.addProxy(node(base.copy(name = "duplicate")))
            val snapshot = dao.getById(duplicate)!!
            dao.updateProxy(snapshot.copy().apply { putProfile(base.copy(server = "changed.test")) })
            assertEquals(0, dao.deleteDuplicateProxy(42, duplicate, snapshot.document))
            assertNotNull(dao.getById(duplicate))
            dao.updateProxy(snapshot)
            dao.deleteById(keeper)
            assertEquals(0, dao.deleteDuplicateProxy(42, duplicate, snapshot.document))
            dao.addProxy(node(base))
            assertEquals(1, dao.deleteDuplicateProxy(42, duplicate, snapshot.document))
            assertEquals(1, dao.getByGroup(42).size)
        } finally { db.close() }
    }

    @Test fun fullRawConfigRejectsProbeBeforeRuntimeConstruction() {
        val raw = """{"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":19876}],"outbounds":[{"type":"direct"}]}"""
        val entity = ProxyEntity(type = ProxyEntity.TYPE_CONFIG, document = ProfileDocument.encode(ProfileDocument(kind = "raw_config", content = raw)))
        assertEquals(raw, ConfigSnapshot.capture(entity, false, false).generate().result.config)
        assertThrows(UnsupportedStandaloneProbe::class.java) { ConfigSnapshot.capture(entity, true, false) }
    }
}
