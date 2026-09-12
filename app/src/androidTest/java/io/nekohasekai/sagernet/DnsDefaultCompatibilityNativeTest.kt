package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.rust.RustBridge
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Production Rust generator + packaged core start; deterministic snapshots never alter Room/preferences. */
@RunWith(AndroidJUnit4::class)
class DnsDefaultCompatibilityNativeTest {
    private fun snapshot(): JsonObject = JsonParser.parseString("""
        {"mode":"snapshot","version":1,"selected":1,"for_test":false,"for_export":false,
        "settings":{"service_mode":"proxy","allow_access":false,"remote_dns":"https://dns.google/dns-query",
        "direct_dns":"https://223.5.5.5/dns-query","enable_dns_routing":true,"fake_dns":false,"sniffing":0,
        "ipv6":1,"log_level":2,"tun":2,"mtu":9000,"mixed_port":2080,
        "resolve_destination":false,"bypass_lan":false,"global_insecure":false,"server_strategy":"","custom":null,
        "tun_v4":"172.19.0.1","tun_v6":"fdfe:dcba:9876::1"},
        "profiles":[{"id":1,"group_id":1,"name":"dns-compat","server":"127.0.0.1",
        "outbound":{"kind":"Socks","server":"127.0.0.1","port":1080,"protocol":2,"username":"","password":""},
        "chain":null,"full_config":null,"custom_outbound":null,"custom_config":null,"mux":null,"uot":false}],
        "groups":[],"rules":[],"selector_ids":[],"selector_order":[],"extra_ids":[]}
    """).asJsonObject.apply {
        ServerSocket(0).use { getAsJsonObject("settings").addProperty("mixed_port", it.localPort) }
    }

    private fun generate(request: JsonObject): JsonObject {
        val output = JsonParser.parseString(RustBridge.generateConfig(request.toString().encodeToByteArray())
            .decodeToString(throwOnInvalidSequence = true)).asJsonObject
        assertEquals(output.toString(), "SUCCESS", output["status"].asString)
        return JsonParser.parseString(output["config"].asString).asJsonObject
    }

    private fun assertDefaultDns(config: JsonObject) {
        assertEquals(JsonParser.parseString("""[
            {"tag":"dns-local","type":"local"},
            {"tag":"dns-direct","type":"https","server":"223.5.5.5","domain_resolver":"dns-local"},
            {"tag":"dns-remote","type":"https","server":"dns.google","domain_resolver":"dns-direct","detour":"proxy"}
        ]"""), config.getAsJsonObject("dns")["servers"])
        assertEquals("dns-remote", config.getAsJsonObject("dns")["final"].asString)
    }

    private fun startAndClose(config: JsonObject) {
        val core = Libcore.newSingBoxInstance(config.toString(), null)
        try { core.start() } finally { core.close() }
    }

    @Test fun defaultHttpsDnsStartsAndClosesWithoutQueryingInternet() {
        val config = generate(snapshot())
        assertDefaultDns(config)
        assertFalse(config.has("http_clients"))
        startAndClose(config)
        println("DNS_DEFAULT_COMPAT default_https=true core_started=true core_closed=true")
    }

    @Test fun generatedHttpClientDownloadsOnlyLoopbackRuleSetAndStarts() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 15000
            val failure = AtomicReference<Throwable?>()
            val requested = AtomicReference<String?>()
            val worker = thread(name = "dns-compat-ruleset", isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 15000
                        val reader = socket.getInputStream().bufferedReader()
                        requested.set(reader.readLine())
                        while (true) { val line = reader.readLine(); if (line == null || line.isEmpty()) break }
                        val body = """{"version":3,"rules":[{"domain":["fixture.invalid"]}]}""".encodeToByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            write(body); flush()
                        }
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
            try {
                val request = snapshot()
                request.add("rules", JsonParser.parseString("""[{
                    "id":1,"domains":"","ip":"",
                    "rule_sets":[{"name":"dns-compat","source":"https://127.0.0.1:${server.localPort}/dns-compat.json","format":"source","match":"destination"}],
                    "port":"","source_port":"","network":"","source":"","protocol":"",
                    "outbound":-1,"uids":[],"package_count":0,"custom":null}]
                """))
                val config = generate(request)
                assertDefaultDns(config)
                assertEquals(JsonParser.parseString("""[{"tag":"default-http-client"}]"""), config["http_clients"])
                assertEquals("default-http-client", config.getAsJsonObject("route")["default_http_client"].asString)
                val ruleSet = config.getAsJsonObject("route").getAsJsonArray("rule_set").single().asJsonObject
                assertEquals("https://127.0.0.1:${server.localPort}/dns-compat.json", ruleSet["url"].asString)
                assertEquals("source", ruleSet["format"].asString)
                // Production references remain HTTPS, as asserted above. This transport
                // fixture changes only that URL to local HTTP; generated HTTP-client and
                // DNS fields are untouched. TLS itself is outside this compatibility test.
                ruleSet.addProperty("url", "http://127.0.0.1:${server.localPort}/dns-compat.json")
                startAndClose(config)
                worker.join(15000)
                assertFalse("Loopback fixture did not finish", worker.isAlive)
                failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
                assertEquals("GET /dns-compat.json HTTP/1.1", requested.get())
                println("DNS_DEFAULT_COMPAT generated_http_client=true loopback_download=true core_started=true core_closed=true")
            } finally {
                server.close()
                worker.join(1000)
            }
        }
    }

    @Test fun customProxyDetoursArePreservedAndStart() {
        val request = snapshot()
        val custom = JsonParser.parseString("""{
            "dns":{"servers":[{"tag":"custom-dns","type":"https","server":"1.1.1.1","detour":"proxy"}],"rules":[],"final":"custom-dns"},
            "http_clients":[{"tag":"custom-http","detour":"proxy"}],"route":{"default_http_client":"custom-http"}
        }""").asJsonObject
        request.getAsJsonObject("settings").add("custom", custom)
        val config = generate(request)
        assertEquals(custom.getAsJsonObject("dns")["servers"], config.getAsJsonObject("dns")["servers"])
        assertEquals(custom["http_clients"], config["http_clients"])
        startAndClose(config)
        println("DNS_DEFAULT_COMPAT custom_proxy_detours_preserved=true core_started=true core_closed=true")
    }
}
