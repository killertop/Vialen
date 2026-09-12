package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.rust.RustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Rust snapshot -> packaged core -> mixed inbound. No Room, preferences, VPN or external network. */
@RunWith(AndroidJUnit4::class)
class ProReviewRoutingNativeTest {
    private fun freePort() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun snapshot(port: Int): JsonObject = JsonParser.parseString("""
        {"mode":"snapshot","version":1,"selected":1,"for_test":false,"for_export":false,
        "settings":{"service_mode":"proxy","allow_access":false,"remote_dns":"tcp://127.0.0.1:9",
        "direct_dns":"local","enable_dns_routing":true,"fake_dns":false,"sniffing":0,
        "ipv6":1,"log_level":2,"tun":2,"mtu":9000,"mixed_port":$port,
        "resolve_destination":false,"bypass_lan":false,"global_insecure":false,"server_strategy":"",
        "custom":null,"tun_v4":"172.19.0.1","tun_v6":"fdfe:dcba:9876::1"},
        "profiles":[{"id":1,"group_id":1,"name":"direct","server":"127.0.0.1",
        "outbound":{"kind":"Socks","server":"127.0.0.1","port":1080,"protocol":2,"username":"","password":""},
        "chain":null,"full_config":null,"custom_outbound":null,"custom_config":null,"mux":null,"uot":false}],
        "groups":[],"rules":[],"selector_ids":[],"selector_order":[],"extra_ids":[]}
    """).asJsonObject

    private fun generate(request: JsonObject): JsonObject {
        val result = JsonParser.parseString(RustBridge.generateConfig(request.toString().encodeToByteArray())
            .decodeToString(throwOnInvalidSequence = true)).asJsonObject
        assertEquals(result.toString(), "SUCCESS", result["status"].asString)
        return result
    }

    private fun http(port: Int, destinationPort: Int, path: String): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        socket.soTimeout = 2500
        socket.getOutputStream().apply {
            write(("GET http://127.0.0.1:$destinationPort/$path HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$destinationPort\r\nConnection: close\r\n\r\n").encodeToByteArray())
            flush()
        }
        socket.getInputStream().bufferedReader().readText()
    }

    /** Verifies the requested origin, then returns a distinct response without contacting it. */
    private class RecordingSocks(private val destinationPort: Int, private val dns: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val requests = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        private val active = AtomicReference<Socket?>()
        private val worker = thread(isDaemon = true, name = "pro-review-socks") {
            try {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (e: IOException) {
                        if (server.isClosed) break else throw e
                    }
                    active.set(client)
                    client.use { socket ->
                        socket.soTimeout = 5000
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        check(input.readUnsignedByte() == 5)
                        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
                        check(methods.contains(0))
                        output.write(byteArrayOf(5, 0)); output.flush()
                        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
                        input.readUnsignedByte()
                        val address = when (input.readUnsignedByte()) {
                            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                            else -> error("Unexpected SOCKS address family")
                        }
                        check(address == "127.0.0.1" && input.readUnsignedShort() == destinationPort)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                        if (dns) {
                            val size = input.readUnsignedShort()
                            check(size in 17..4096)
                            val query = ByteArray(size).also(input::readFully)
                            check(query[4] == 0.toByte() && query[5] == 1.toByte())
                            var end = 12
                            while (query[end] != 0.toByte()) {
                                val label = query[end].toInt() and 255
                                check(label in 1..63)
                                end += label + 1
                                check(end < query.size)
                            }
                            end += 5 // terminating zero, QTYPE and QCLASS
                            check(end <= query.size)
                            check(query[end - 3] == 1.toByte() && query[end - 1] == 1.toByte())
                            val response = query.copyOf(end).apply {
                                this[2] = 0x81.toByte(); this[3] = 0x80.toByte()
                                this[6] = 0; this[7] = 1
                                this[8] = 0; this[9] = 0; this[10] = 0; this[11] = 0
                            } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4,
                                203.toByte(), 0, 113, 77)
                            requests.incrementAndGet()
                            output.write(byteArrayOf((response.size shr 8).toByte(), response.size.toByte()))
                            output.write(response); output.flush()
                        } else {
                            val reader = input.bufferedReader()
                            check(reader.readLine()?.startsWith("GET ") == true)
                            var bytes = 0
                            while (true) {
                                val line = reader.readLine() ?: error("Missing HTTP headers")
                                bytes += line.length; check(bytes < 16384)
                                if (line.isEmpty()) break
                            }
                            requests.incrementAndGet()
                            val body = "PRO_REVIEW_VIA_SOCKS"
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".encodeToByteArray())
                            output.flush()
                        }
                    }
                    active.set(null)
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        override fun close() {
            server.close(); active.get()?.close(); worker.join(6000)
            check(!worker.isAlive) { "SOCKS fixture worker did not stop" }
        }
    }

    @Test fun reservedAndDuplicateNamesKeepStableTagsAndActuallyUseSocks() = runBlocking {
        withContext(Dispatchers.IO) {
            LoopbackHttpFixture().use { origin ->
                origin.reply.set(LoopbackHttpFixture.Reply(body = "PRO_REVIEW_DIRECT_ORIGIN"))
                assertTrue(http(origin.port, origin.port, "control").contains("PRO_REVIEW_DIRECT_ORIGIN"))
                origin.requests.set(0)
                RecordingSocks(origin.port).use { socks ->
                    val request = snapshot(freePort())
                    val seed = request.getAsJsonArray("profiles")[0].asJsonObject.deepCopy()
                    request.add("profiles", JsonParser.parseString("[]"))
                    listOf("direct", "bypass", "proxy", "duplicate", "duplicate").forEachIndexed { index, name ->
                        request.getAsJsonArray("profiles").add(seed.deepCopy().apply {
                            addProperty("id", index + 1); addProperty("name", name)
                            getAsJsonObject("outbound").addProperty("port", socks.port)
                        })
                    }
                    request.add("groups", JsonParser.parseString("""[{"id":1,"selector":true,"front":0,"landing":0}]"""))
                    request.add("selector_ids", JsonParser.parseString("[1,2,3,4,5]"))
                    request.add("selector_order", JsonParser.parseString("[1,2,3,4,5]"))
                    val originalTags = generate(request)["tags"].deepCopy()
                    for (renamed in listOf(false, true)) {
                        if (renamed) request.getAsJsonArray("profiles").forEach {
                            it.asJsonObject.addProperty("name", "renamed")
                        }
                        for (selected in 1..5) {
                            request.addProperty("selected", selected)
                            val mixedPort = freePort()
                            request.getAsJsonObject("settings").addProperty("mixed_port", mixedPort)
                            val generated = generate(request)
                            assertEquals(originalTags, generated["tags"])
                            val config = JsonParser.parseString(generated["config"].asString).asJsonObject
                            assertEquals("proxy", config.getAsJsonObject("route")["final"].asString)
                            val selector = config.getAsJsonArray("outbounds").single {
                                it.asJsonObject["tag"].asString == "proxy"
                            }.asJsonObject
                            assertEquals("g-$selected", selector["default"].asString)
                            val before = socks.requests.get()
                            val core = Libcore.newSingBoxInstance(generated["config"].asString, null)
                            try {
                                core.start()
                                val response = http(mixedPort, origin.port, "selector-$selected-$renamed")
                                assertTrue(response, response.startsWith("HTTP/1.1 200"))
                                assertTrue(response, response.endsWith("PRO_REVIEW_VIA_SOCKS"))
                            } finally { core.close() }
                            assertEquals(before + 1, socks.requests.get())
                            assertEquals(0, origin.requests.get())
                            socks.failure.get()?.let { throw AssertionError("SOCKS fixture failed", it) }
                        }
                    }
                    println("PRO_REVIEW_ROUTING selector_exchanges=${socks.requests.get()} rename_tags_stable=true origin_requests=0")
                }
            }
        }
    }

    @Test fun generatedRemoteTcpDnsQueriesActuallyTravelThroughSocks() = runBlocking {
        withContext(Dispatchers.IO) {
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { origin ->
                val originRequests = AtomicInteger()
                val originFailure = AtomicReference<Throwable?>()
                val originWorker = thread(isDaemon = true, name = "pro-review-dns-origin") {
                    try {
                        while (!origin.isClosed) {
                            val socket = try { origin.accept() } catch (e: IOException) {
                                if (origin.isClosed) break else throw e
                            }
                            socket.use { originRequests.incrementAndGet() }
                        }
                    } catch (e: Throwable) { originFailure.set(e) }
                }
                try {
                    RecordingSocks(origin.localPort, dns = true).use { socks ->
                        val udpPort = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                        val request = snapshot(freePort())
                        request.getAsJsonArray("profiles")[0].asJsonObject.getAsJsonObject("outbound")
                            .addProperty("port", socks.port)
                        request.getAsJsonObject("settings").apply {
                            addProperty("remote_dns", "tcp://127.0.0.1:${origin.localPort}")
                            addProperty("direct_dns", "tcp://127.0.0.1:${origin.localPort}")
                            // Only install the test ingress. Rust still owns the DNS servers and detours.
                            add("custom", JsonParser.parseString("""{
                                "inbounds":[{"type":"direct","tag":"dns-probe","listen":"127.0.0.1",
                                    "listen_port":$udpPort,"network":"udp"}],
                                "route":{"rules":[{"inbound":["dns-probe"],"action":"hijack-dns"}]}
                            }"""))
                        }
                        val generated = generate(request)
                        val config = JsonParser.parseString(generated["config"].asString).asJsonObject
                        val remote = config.getAsJsonObject("dns").getAsJsonArray("servers").single {
                            it.asJsonObject["tag"].asString == "dns-remote"
                        }.asJsonObject
                        assertEquals("tcp", remote["type"].asString)
                        assertEquals("proxy", remote["detour"].asString)
                        assertEquals("dns-direct", remote["domain_resolver"].asString)
                        val core = Libcore.newSingBoxInstance(generated["config"].asString, null)
                        try {
                            core.start()
                            // One ordinary A/IN question, no EDNS or cache reuse.
                            val query = byteArrayOf(0x42, 0x19, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                                5, 112, 114, 111, 98, 101, 7, 105, 110, 118, 97, 108, 105, 100, 0, 0, 1, 0, 1)
                            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { client ->
                                client.soTimeout = 5000
                                client.connect(InetAddress.getByName("127.0.0.1"), udpPort)
                                client.send(DatagramPacket(query, query.size))
                                val packet = DatagramPacket(ByteArray(4096), 4096)
                                client.receive(packet)
                                val response = packet.data.copyOf(packet.length)
                                assertTrue("Short DNS response", response.size >= 16)
                                assertEquals(0x42.toByte(), response[0]); assertEquals(0x19.toByte(), response[1])
                                assertEquals("DNS RCODE", 0, response[3].toInt() and 15)
                                assertEquals("DNS answer count", 1, ((response[6].toInt() and 255) shl 8) or (response[7].toInt() and 255))
                                assertArrayEquals(byteArrayOf(203.toByte(), 0, 113, 77), response.takeLast(4).toByteArray())
                            }
                        } finally { core.close() }
                        // The resolver may repeat an exchange; every completed exchange is
                        // checked by the SOCKS fixture against the intended DNS endpoint.
                        assertTrue("TCP DNS must reach SOCKS", socks.requests.get() >= 1)
                        socks.failure.get()?.let { throw AssertionError("DNS SOCKS fixture failed", it) }
                        assertEquals("DNS must not contact the direct origin", 0, originRequests.get())
                        println("PRO_REVIEW_ROUTING remote_tcp_dns_via_socks=true exchanges=${socks.requests.get()} sentinel=203.0.113.77 origin_requests=0")
                    }
                } finally {
                    origin.close(); originWorker.join(6000)
                    assertFalse("DNS origin worker did not stop", originWorker.isAlive)
                }
                originFailure.get()?.let { throw AssertionError("DNS origin fixture failed", it) }
                assertEquals(0, originRequests.get())
            }
        }
    }

    @Test fun offlineStandaloneWireguardNeverFallsBackToDirectOrigin() = runBlocking {
        withContext(Dispatchers.IO) {
            LoopbackHttpFixture().use { origin ->
                origin.reply.set(LoopbackHttpFixture.Reply(body = "PRO_REVIEW_DIRECT_ORIGIN"))
                assertTrue(http(origin.port, origin.port, "control").contains("PRO_REVIEW_DIRECT_ORIGIN"))
                origin.requests.set(0)
                // Bound but unresponsive UDP peer: all traffic remains local to the phone.
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { offlinePeer ->
                    val mixedPort = freePort()
                    val request = snapshot(mixedPort)
                    request.getAsJsonArray("profiles")[0].asJsonObject.add("outbound", JsonParser.parseString("""
                        {"kind":"WireGuard","server":"127.0.0.1","port":${offlinePeer.localPort},
                        "local_address":"10.77.0.2/32","private_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                        "public_key":"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=","pre_shared_key":"","mtu":1420,"reserved":""}
                    """))
                    val generated = generate(request)
                    val config = JsonParser.parseString(generated["config"].asString).asJsonObject
                    assertEquals("proxy", config.getAsJsonObject("route")["final"].asString)
                    assertEquals("proxy", config.getAsJsonArray("endpoints").single().asJsonObject["tag"].asString)
                    val core = Libcore.newSingBoxInstance(generated["config"].asString, null)
                    try {
                        core.start()
                        // A missing mixed listener must fail this test, not count as fail-closed.
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", mixedPort), 2000) }
                        val response = try { http(mixedPort, origin.port, "offline-wg") } catch (_: IOException) { "" }
                        assertFalse(response, response.contains("PRO_REVIEW_DIRECT_ORIGIN"))
                        assertFalse(response, response.startsWith("HTTP/1.1 200"))
                    } finally { core.close() }
                    assertEquals("Offline WireGuard must not fall back to the direct origin", 0, origin.requests.get())
                    println("PRO_REVIEW_ROUTING offline_wireguard_fail_closed=true origin_requests=0")
                }
            }
        }
    }
}
