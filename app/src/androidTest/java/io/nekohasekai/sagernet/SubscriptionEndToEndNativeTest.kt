package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.RawUpdater
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class SubscriptionEndToEndNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val benchmarkForeground = BenchmarkForegroundRule()
    @get:Rule val selectionState = ProfileSelectionStateRule()

    @Test fun completeFormatsReachRealHttpAndRoomPersistence() = runBlocking {
        val db=SagerDatabase.instance
        LoopbackHttpFixture().use { server ->
            val sub=SubscriptionBean().apply {initializeDefaultValues();link="http://127.0.0.1:${server.port}/formats";deduplication=false;forceResolve=false}
            val group=ProxyGroup(name="Core-formats-${System.nanoTime()}",type=GroupType.SUBSCRIPTION,subscription=sub)
            group.id=db.groupDao().createGroup(group)
            try {
                val formats=listOf(
                    "socks5://127.0.0.1:1080#SOCKS\nhttp://user:pass@127.0.0.1:8080#HTTP",
                    "proxies: [{type: http, name: HTTP, server: 127.0.0.1, port: 8080}, {type: anytls, name: AnyTLS, server: 127.0.0.1, port: 443, password: synthetic}]",
                    """{"outbounds":[{"type":"socks","tag":"Custom","server":"127.0.0.1","server_port":1080}]}""",
                    "[Interface]\nAddress=10.0.0.1/32\nPrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nMTU=1420\n[Peer]\nEndpoint=127.0.0.1:51820\nPublicKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    Base64.getEncoder().encodeToString("http://user:pass@127.0.0.1:8080#HTTP\nanytls://pass@127.0.0.1:443#AnyTLS".toByteArray())
                )
                for (text in formats) {
                    val expectedNames = listOf(listOf("SOCKS", "HTTP"), listOf("HTTP", "AnyTLS"), listOf("Custom"), listOf("WireGuard"), listOf("HTTP", "AnyTLS"))[formats.indexOf(text)]
                    server.reply.set(LoopbackHttpFixture.Reply(body=text))
                    RawUpdater.doUpdate(group,sub,null,false)
                    val rows=db.proxyDao().getByGroup(group.id)
                    if (formats.indexOf(text) != 3) assertEquals(expectedNames, rows.map { it.displayName() })
                    assertEquals(if (formats.indexOf(text) == 3) listOf("wireguard") else listOf(listOf("socks", "http"), listOf("http", "anytls"), listOf("socks"), emptyList(), listOf("http", "anytls"))[formats.indexOf(text)], rows.map { it.requireProfile().type })
                    val ids=rows.map {it.id}
                    RawUpdater.doUpdate(group,sub,null,false)
                    assertEquals(ids,db.proxyDao().getByGroup(group.id).map {it.id})
                }
                assertEquals(formats.size*2,server.requests.get())
            } finally {db.runInTransaction {db.proxyDao().deleteByGroup(group.id);db.groupDao().deleteById(group.id)}}
        }
    }
    @Test fun duplicateNamesAndStableIdsReachRoom() = runBlocking {
        val db = SagerDatabase.instance
        LoopbackHttpFixture().use { server ->
            val sub = SubscriptionBean().apply {
                initializeDefaultValues(); link = "http://127.0.0.1:${server.port}/duplicates"
                deduplication = false; forceResolve = false
            }
            val group = ProxyGroup(name = "Core-duplicates-${System.nanoTime()}", type = GroupType.SUBSCRIPTION, subscription = sub)
            group.id = db.groupDao().createGroup(group)
            try {
                val names = List(12) { "A" } + listOf("A (0)", "A (1)", "A (1) (1)", "节点😀", "节点😀")
                val text = names.mapIndexed { index, label ->
                    "socks5://127.0.0.1:${1080 + index}#${URLEncoder.encode(label, "UTF-8").replace("+", "%20")}"
                }.joinToString("\n")
                val expected = names
                server.reply.set(LoopbackHttpFixture.Reply(body = text))
                RawUpdater.doUpdate(group, sub, null, false)
                val rows = db.proxyDao().getByGroup(group.id)
                assertEquals(expected, rows.map { it.displayName() })
                assertEquals(names.indices.map { 1080 + it }, rows.map { it.requireProfile().port })
                RawUpdater.doUpdate(group, sub, null, false)
                assertEquals(rows.map { it.id }, db.proxyDao().getByGroup(group.id).map { it.id })
            } finally {
                db.runInTransaction { db.proxyDao().deleteByGroup(group.id); db.groupDao().deleteById(group.id) }
                assertNull(db.groupDao().getById(group.id))
            }
        }
    }
    @Test fun actualHttpFetchDecodeBatchDedupDiffAndRoomRecoverTogether() = runBlocking {
        val db = SagerDatabase.instance
        val originalGroups = db.groupDao().allGroups().map { it.id }.toSet()
        LoopbackHttpFixture().use { server ->
            val sub = SubscriptionBean().apply { initializeDefaultValues(); link = "http://127.0.0.1:${server.port}/subscription"; deduplication = true; forceResolve = false }
            val group = ProxyGroup(name = "Core-E2E-${System.nanoTime()}", type = GroupType.SUBSCRIPTION, subscription = sub)
            group.id = db.groupDao().createGroup(group)
            var expectedNames = listOf("A", "B", "C", "D", "E", "F")
            var successes = 0
            val ui = object : GroupManager.Interface {
                override suspend fun confirm(message: String) = error("Unexpected confirmation")
                override suspend fun alert(message: String) = error("Unexpected alert")
                override suspend fun onUpdateFailure(group: ProxyGroup, message: String) = error(message)
                override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String,String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) {
                    // Notification must observe the already committed transaction.
                    assertEquals(expectedNames, db.proxyDao().getByGroup(group.id).map { it.displayName() })
                    successes++
                }
            }
            fun publish(links: List<String>) {
                server.reply.set(LoopbackHttpFixture.Reply(body = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray())))
            }
            try {
                val initial = listOf("trojan://pw@a.example:443#A", "tuic://00000000-0000-4000-8000-000000000001:pw@b.example:443#B",
                    "anytls://pw@c.example:443#C", "hy2://pw@d.example:443#D",
                    "vless://00000000-0000-4000-8000-000000000001@e.example:443?security=tls#E", "http://f.example:8080#F",
                    "trojan://pw@a.example:443#duplicate")
                publish(initial)
                RawUpdater.doUpdate(group, sub, ui, false)
                val oldRows = db.proxyDao().getByGroup(group.id)
                val oldIds = oldRows.associate { it.displayName() to it.id }
                expectedNames = listOf("F", "A", "new")
                publish(listOf(initial[5], "trojan://changed@changed.example:443#A", "socks5://new.example:1080#new"))
                RawUpdater.doUpdate(group, sub, ui, false)
                val rows = db.proxyDao().getByGroup(group.id)
                assertEquals(oldIds["F"], rows[0].id)
                assertEquals(oldIds["A"], rows[1].id)
                assertEquals(listOf(1L,2L,3L), rows.map { it.userOrder })
                assertEquals("changed", rows[1].requireProfile().trojan!!.password)
                RawUpdater.doUpdate(group, sub, ui, false)
                assertEquals(rows.map { it.id }, db.proxyDao().getByGroup(group.id).map { it.id })
                val lastUpdated = db.groupDao().getById(group.id)!!.subscription!!.lastUpdated
                server.reply.set(LoopbackHttpFixture.Reply(503, "invalid subscription"))
                assertTrue(runCatching { RawUpdater.doUpdate(group, sub, ui, false) }.isFailure)
                assertEquals(3, successes)
                assertEquals(lastUpdated, db.groupDao().getById(group.id)!!.subscription!!.lastUpdated)
                assertEquals(rows.map { it.id }, db.proxyDao().getByGroup(group.id).map { it.id })
                // Empty and partial subscriptions must retain the committed group.
                server.reply.set(LoopbackHttpFixture.Reply(body = "[]"))
                assertTrue(runCatching { RawUpdater.doUpdate(group, sub, ui, false) }.isFailure)
                assertEquals(rows.map { it.id }, db.proxyDao().getByGroup(group.id).map { it.id })
                assertEquals(3, successes)
                server.reply.set(LoopbackHttpFixture.Reply(body = "trojan://pw@valid.example:443#valid\nunsupported://bad"))
                assertTrue(runCatching { RawUpdater.doUpdate(group, sub, ui, false) }.isFailure)
                assertEquals(rows.map { it.id }, db.proxyDao().getByGroup(group.id).map { it.id })
                assertEquals(3, successes)
                assertEquals(6, server.requests.get())
            } finally {
                db.runInTransaction { db.proxyDao().deleteByGroup(group.id); db.groupDao().deleteById(group.id) }
            }
        }
    }

    @Test fun contentUriSubscriptionImportsSuccessfullyAgainstRoom() = runBlocking {
        val db = SagerDatabase.instance
        val originalGroups = db.groupDao().allGroups().map { it.id }.toSet()
        val tempFile = java.io.File(SagerNet.application.cacheDir, "content_sub_test_${System.nanoTime()}.txt")
        try {
            tempFile.writeText("trojan://pw@node1.example:443#ContentTrojan\n")
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                SagerNet.application,
                "${SagerNet.application.packageName}.cache",
                tempFile
            )
            val sub = SubscriptionBean().apply {
                initializeDefaultValues()
                link = contentUri.toString()
                deduplication = false
                forceResolve = false
            }
            val group = ProxyGroup(name = "Content-E2E-${System.nanoTime()}", type = GroupType.SUBSCRIPTION, subscription = sub)
            group.id = db.groupDao().createGroup(group)
            try {
                RawUpdater.doUpdate(group, sub, null, false)
                val rows = db.proxyDao().getByGroup(group.id)
                assertEquals(1, rows.size)
                assertEquals("ContentTrojan", rows[0].displayName())
                assertEquals("trojan", rows[0].requireProfile().type)
            } finally {
                db.runInTransaction {
                    db.proxyDao().deleteByGroup(group.id)
                    db.groupDao().deleteById(group.id)
                }
            }
        } finally {
            tempFile.delete()
        }
        assertEquals(originalGroups, db.groupDao().allGroups().map { it.id }.toSet())
    }
}
