package io.nekohasekai.sagernet

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.core.CoreClient
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import io.nekohasekai.sagernet.group.SubscriptionPersistence
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real gomobile calls and Room; all network traffic terminates at the owned loopback fixture. */
@RunWith(AndroidJUnit4::class)
class NewClientCoreNativeTest {
    @get:Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule()
    @get:Rule val selectionState = ProfileSelectionStateRule()

    private suspend fun withGroup(body: suspend (ProxyGroup) -> Unit) {
        val db = SagerDatabase.instance
        val group = ProxyGroup(name = "new-client-${System.nanoTime()}", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply { initializeDefaultValues() })
        group.id = db.groupDao().createGroup(group)
        selectionState.preservingFailure({
            DataStore.directDns = "local"
            DataStore.remoteDns = "local"
            DataStore.globalCustomConfig = ""
            DataStore.globalAllowInsecure = false
            body(group)
        }, {
            db.runInTransaction {
                db.proxyDao().deleteByGroup(group.id)
                db.groupDao().deleteById(group.id)
            }
            assertNull(db.groupDao().getById(group.id))
        })
    }

    private fun initializeWithoutStarting(config: String) {
        val document = JsonParser.parseString(config).asJsonObject
        assertFalse(document.getAsJsonArray("inbounds")?.any { it.asJsonObject["type"].asString == "tun" } ?: false)
        val instance = Libcore.newSingBoxInstance(config, LocalResolverImpl)
        instance.close() // box.New validates the pinned registry; no Start, listener, or TUN.
    }

    @Test fun formatsAdvancedFieldsAndPartialFailureRoundTripThroughRealCore() = runBlocking {
        withGroup { group ->
            val advanced = Profile(id = "synthetic-provider", name = "Advanced", type = "trojan", server = "127.0.0.1", port = 443,
                tls = Profile.Tls(serverName = "synthetic.example", alpn = listOf("http/1.1"), fingerprint = "chrome"),
                transport = Profile.Transport(type = "ws", host = listOf("front.synthetic.example"),
                    headers = mapOf("X-Synthetic" to listOf("first", "second")), path = "/socket", maxEarlyData = 1024,
                    earlyDataHeaderName = "Sec-WebSocket-Protocol"), trojan = Profile.Password("synthetic-password"))
            val formats = listOf(
                "links" to "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?security=tls&sni=synthetic.example&type=ws&path=%2Furi#URI",
                "clash" to "proxies: [{name: Clash, type: trojan, server: 127.0.0.1, port: 443, password: synthetic, sni: synthetic.example, alpn: [http/1.1], network: ws, ws-opts: {path: /clash, headers: {Host: front.synthetic.example, X-Synthetic: header}}}]",
                "wireguard" to "[Interface]\nPrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\nAddress=10.23.0.2/32\nMTU=1280\n[Peer]\nPublicKey=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\nPresharedKey=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=\nEndpoint=127.0.0.1:51820\nAllowedIPs=0.0.0.0/0,::/0\nPersistentKeepalive=25\n",
                "profiles" to CoreClient.exportProfiles(listOf(advanced)),
            )
            for ((format, text) in formats) {
                val imported = CoreClient.importProfiles(text, format).requireComplete()
                assertEquals(1, imported.size)
                if (format == "profiles") assertEquals(advanced, imported.single())
                if (format == "wireguard") {
                    assertEquals(listOf("0.0.0.0/0", "::/0"), imported.single().wireguard!!.allowedIps)
                    assertEquals(25L, imported.single().wireguard!!.persistentKeepalive)
                }
                SubscriptionPersistence.apply(SagerDatabase.instance, group, imported)
                val row = SagerDatabase.proxyDao.getByGroup(group.id).single()
                assertEquals(imported.single().copy(id = row.requireProfile().id), row.requireProfile())
                assertTrue(row.requireProfile().id.isNotBlank())
                assertNotEquals("synthetic-provider", row.requireProfile().id)
                val config = ConfigSnapshot.capture(row, forTest = true, forExport = false).generate().result.config
                initializeWithoutStarting(config)
                if (format == "profiles") {
                    val outbound = JsonParser.parseString(config).asJsonObject.getAsJsonArray("outbounds")
                        .map { it.asJsonObject }.single { it["type"].asString == "trojan" }
                    assertEquals("synthetic-password", outbound["password"].asString)
                    assertEquals("synthetic.example", outbound.getAsJsonObject("tls")["server_name"].asString)
                    assertEquals("/socket", outbound.getAsJsonObject("transport")["path"].asString)
                    assertEquals(1024, outbound.getAsJsonObject("transport")["max_early_data"].asInt)
                    assertEquals("Sec-WebSocket-Protocol", outbound.getAsJsonObject("transport")["early_data_header_name"].asString)
                    assertEquals("chrome", outbound.getAsJsonObject("tls").getAsJsonObject("utls")["fingerprint"].asString)
                    assertEquals(listOf("first", "second"), outbound.getAsJsonObject("transport")
                        .getAsJsonObject("headers").getAsJsonArray("X-Synthetic").map { it.asString })
                }
            }
            val before = SagerDatabase.proxyDao.getByGroup(group.id).map { it.id to it.document }
            val partial = CoreClient.importProfiles("trojan://synthetic@127.0.0.1:443#valid\nunknown://invalid", "links")
            assertTrue(partial.profiles.isNotEmpty())
            assertTrue(partial.issues.any { it.severity == "error" })
            assertTrue(runCatching {
                SubscriptionPersistence.apply(SagerDatabase.instance, group, partial.requireComplete())
            }.isFailure)
            assertEquals(before, SagerDatabase.proxyDao.getByGroup(group.id).map { it.id to it.document })
        }
    }

    @Test fun storedProfileUsesActualSingBoxForLoopbackHttp() = runBlocking {
        withGroup { group ->
            val nonce = "new-client-http-${System.nanoTime()}"
            LoopbackSocksFixture(nonce).use { fixture ->
                val profiles = CoreClient.importProfiles("socks5://127.0.0.1:${fixture.port}#$nonce", "links").requireComplete()
                SubscriptionPersistence.apply(SagerDatabase.instance, group, profiles)
                val row = SagerDatabase.proxyDao.getByGroup(group.id).single()
                initializeWithoutStarting(ConfigSnapshot.capture(row, true, false).generate().result.config)
                val rtt = withTimeout(6000) { TestInstance(row, "http://198.18.0.254/$nonce", 4000).doTest() }
                assertTrue(rtt >= 0)
                assertEquals(2, fixture.requests.get())
                println("NEW_CLIENT_LOOPBACK requests=${fixture.requests.get()} rtt_ms=$rtt vpn_started=false")
            }
        }
    }

    @Test fun thousandProfilesImportSaveAndCompileMeasurement() = runBlocking {
        withGroup { group ->
            val text = (0 until 1000).joinToString("\n") { "socks5://127.0.0.1:${10000 + it}#Synthetic-$it" }
            val memoryBefore = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val started = SystemClock.elapsedRealtimeNanos()
            val profiles = CoreClient.importProfiles(text, "links").requireComplete()
            val importedAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(1000, profiles.size)
            SubscriptionPersistence.apply(SagerDatabase.instance, group, profiles)
            val rows = SagerDatabase.proxyDao.getByGroup(group.id)
            val savedAt = SystemClock.elapsedRealtimeNanos()
            assertEquals(1000, rows.size)
            assertEquals(1000, rows.map { it.requireProfile().id }.toSet().size)
            assertEquals(profiles.map { it.copy(id = "") }, rows.map { it.requireProfile().copy(id = "") })
            // Assemble a deterministic 1000-candidate snapshot without reading unrelated user rules.
            val request = ConfigSnapshot.assemble(rows.first().id, rows.associateBy { it.id }, mapOf(group.id to group),
                rows.map { it.id }, emptyList(),
                ConfigSnapshot.obj("dns" to ConfigSnapshot.obj("direct" to ConfigSnapshot.obj("type" to "local"),
                    "remote" to ConfigSnapshot.obj("type" to "local"))),
                ConfigSnapshot.obj("vpn" to false), "probe")
            val plan = CoreClient.compile(request)
            val config = plan["config"].asString
            val compiledAt = SystemClock.elapsedRealtimeNanos()
            val outbounds = JsonParser.parseString(config).asJsonObject.getAsJsonArray("outbounds")
            assertEquals(1000, outbounds.count { it.asJsonObject["type"].asString == "socks" })
            initializeWithoutStarting(config)
            val finished = SystemClock.elapsedRealtimeNanos()
            val memoryAfter = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
            val allocatedAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val allocated = if (allocatedBefore != null && allocatedAfter != null) (allocatedAfter - allocatedBefore).toString() else "unavailable"
            fun ms(end: Long, start: Long) = (end - start) / 1_000_000.0
            println("NEW_CLIENT_1000 nodes=1000 import_ms=${ms(importedAt, started)} save_reload_ms=${ms(savedAt, importedAt)} compile_ms=${ms(compiledAt, savedAt)} init_close_ms=${ms(finished, compiledAt)} total_ms=${ms(finished, started)} pss_before_kib=$memoryBefore pss_after_kib=$memoryAfter jvm_allocated_bytes=$allocated")
        }
    }
}
