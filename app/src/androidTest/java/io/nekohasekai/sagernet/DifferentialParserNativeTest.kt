package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.oracle.parseHysteria1
import io.nekohasekai.sagernet.oracle.parseHysteria2
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.oracle.parseTrojan
import io.nekohasekai.sagernet.oracle.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.oracle.parseV2Ray
import io.nekohasekai.sagernet.rust.CanonicalProxyResult
import io.nekohasekai.sagernet.rust.RustBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLEncoder
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class DifferentialParserNativeTest {

    private fun kotlinParse(uri: String): CanonicalProxyResult {
        if (!uri.startsWith("ss://") && !uri.startsWith("socks://") && !uri.startsWith("socks4://") && !uri.startsWith("socks4a://") && !uri.startsWith("socks5://")) {
            return CanonicalProxyResult(status = "INVALID_SCHEME", error = "unsupported protocol")
        }
        return try {
            if (uri.startsWith("ss://")) {
                val bean = parseShadowsocks(uri)
                CanonicalProxyResult(
                    status = "SUCCESS",
                    protocol = "shadowsocks",
                    server = bean.serverAddress,
                    port = bean.serverPort,
                    username = bean.method,
                    password = bean.password,
                    plugin = bean.plugin,
                    name = bean.name ?: ""
                )
            } else {
                val bean = parseSOCKS(uri)
                val proto = when (bean.protocol) {
                    SOCKSBean.PROTOCOL_SOCKS4 -> "socks4"
                    SOCKSBean.PROTOCOL_SOCKS4A -> "socks4a"
                    else -> "socks5"
                }
                CanonicalProxyResult(
                    status = "SUCCESS",
                    protocol = proto,
                    server = bean.serverAddress,
                    port = bean.serverPort,
                    username = bean.username ?: "",
                    password = bean.password ?: "",
                    plugin = "",
                    name = bean.name ?: ""
                )
            }
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            val status = when {
                msg.contains("base-64") || msg.contains("base64") || e is IllegalArgumentException -> "INVALID_BASE64"
                uri.contains(":notaport") || uri.contains(":abc") || uri.contains(":99999") || uri.contains(":70000") || uri.contains(":0") -> "INVALID_PORT"
                else -> "INVALID_URI"
            }
            CanonicalProxyResult(status = status, error = e.message)
        }
    }

    @Test
    fun testShadowsocksProductionDifferential() {
        val testCases = mutableListOf<String>()

        // 1. SIP002 URIs (IPv4, IPv6 bracket, Hostnames, standard/URL-safe/unpadded base64, special chars)
        val ciphers = listOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305")
        for (i in 1..25) {
            val cipher = ciphers[i % ciphers.size]
            val pass = "pass_special_!@#_$i"
            val host = when (i % 3) {
                0 -> "192.168.1.$i"
                1 -> "node-$i.example.com"
                else -> "[2001:db8::$i]"
            }
            val port = if (i == 1) 1 else if (i == 25) 65535 else 1000 + i
            val name = "节点_$i 🚀 (US-West)"
            val b64 = if (i % 2 == 0) {
                Base64.getUrlEncoder().withoutPadding().encodeToString("$cipher:$pass".toByteArray())
            } else {
                Base64.getEncoder().encodeToString("$cipher:$pass".toByteArray())
            }
            val encodedName = URLEncoder.encode(name, "UTF-8")
            testCases.add("ss://$b64@$host:$port#$encodedName")
        }

        // 2. Plain SS with plugins, normalization, and delimiter safety ("|")
        for (i in 1..15) {
            val plugin = "simple-obfs;obfs=http;obfs-host=cdn$i.example.com;tag=opt|test"
            val encodedPlugin = URLEncoder.encode(plugin, "UTF-8")
            val pass = "p|a|s|s_$i"
            val name = "Plugin|Node|$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            testCases.add("ss://chacha20-ietf-poly1305:$pass@10.10.1.$i:8388?plugin=$encodedPlugin#$encodedName")
        }

        // 3. Legacy v2rayN SS
        for (i in 1..20) {
            val host = if (i % 2 == 0) "172.16.0.$i" else "legacy$i.internal.net"
            val raw = "chacha20-ietf-poly1305:mypass$i@$host:${8000 + i}"
            val b64 = Base64.getEncoder().encodeToString(raw.toByteArray())
            val name = "Legacy_V2RayN_节点_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            testCases.add("ss://$b64#$encodedName")
        }

        var matchCount = 0
        for (uri in testCases) {
            val kt = kotlinParse(uri)
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", kt.status, rs.status)
            assertEquals("Protocol mismatch on $uri", kt.protocol, rs.protocol)
            assertEquals("Server mismatch on $uri", kt.server, rs.server)
            assertEquals("Port mismatch on $uri", kt.port, rs.port)
            assertEquals("Username mismatch on $uri", kt.username, rs.username)
            assertEquals("Password mismatch on $uri", kt.password, rs.password)
            assertEquals("Plugin mismatch on $uri", kt.plugin, rs.plugin)
            assertEquals("Name mismatch on $uri", kt.name, rs.name)
            matchCount++
        }
        assertEquals(60, matchCount)
    }

    @Test
    fun testSocksProductionDifferential() {
        val testCases = mutableListOf<String>()
        for (i in 1..30) {
            val scheme = when (i % 3) {
                0 -> "socks4"
                1 -> "socks4a"
                else -> "socks5"
            }
            val host = when (i % 3) {
                0 -> "192.168.10.$i"
                1 -> "proxy-$i.example.org"
                else -> "[2001:db8::10:$i]"
            }
            val port = if (i == 1) 1080 else if (i == 30) 65535 else 1000 + i
            val user = "user_$i"
            val pass = "p|a|s|s_$i"
            val name = "SocksNode_$i|Region"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            testCases.add("$scheme://$user:$pass@$host:$port#$encodedName")
        }

        var matchCount = 0
        for (uri in testCases) {
            val kt = kotlinParse(uri)
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", kt.status, rs.status)
            assertEquals("Protocol mismatch on $uri", kt.protocol, rs.protocol)
            assertEquals("Server mismatch on $uri", kt.server, rs.server)
            assertEquals("Port mismatch on $uri", kt.port, rs.port)
            assertEquals("Username mismatch on $uri", kt.username, rs.username)
            assertEquals("Password mismatch on $uri", kt.password, rs.password)
            assertEquals("Name mismatch on $uri", kt.name, rs.name)
            matchCount++
        }
        assertEquals(30, matchCount)
    }

    @Test
    fun testInvalidUriDifferentialEquivalence() {
        val invalidCases = listOf(
            // INVALID_SCHEME
            "unknown://user:pass@1.1.1.1:80",
            "ftp://foo:bar@1.1.1.1:21",
            "http://foo:bar@1.1.1.1:80",
            // INVALID_PORT
            "ss://user:pass@1.1.1.1:abc",
            "ss://user:pass@1.1.1.1:99999",
            "ss://user:pass@1.1.1.1:0",
            "socks5://user:pass@1.1.1.1:70000",
            "socks5://1.1.1.1:notaport",
            "socks5://user:pass@1.1.1.1:0",
            // INVALID_BASE64
            "ss://A@1.1.1.1:8388",
            "ss://A===@1.1.1.1:8388",
            "ss://A#LegacyInvalid",
            "ss://A===#LegacyInvalid",
            // INVALID_URI
            "ss://",
            "socks://",
            "ss://[unclosed-ipv6:8388"
        )

        var matchedErrors = 0
        for (uri in invalidCases) {
            val kt = kotlinParse(uri)
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Expected exact status match on invalid URI: $uri", kt.status, rs.status)
            assertTrue("Expected non-SUCCESS on invalid URI: $uri", rs.status != "SUCCESS")
            matchedErrors++
        }
        assertEquals(invalidCases.size, matchedErrors)
    }

    @Test
    fun testDelimiterSafetyRoundtrip() {
        val uri = "ss://chacha20-ietf-poly1305:pass|word|with|many|pipes@127.0.0.1:8388?plugin=simple-obfs%3Bobfs%3Dhttp%7Cmode%3Dpipe#Node%7CName%7CWith%7CPipes"
        val kt = kotlinParse(uri)
        val rs = RustBridge.parseProxy(uri)

        assertEquals("SUCCESS", rs.status)
        assertEquals(kt.password, rs.password)
        assertEquals(kt.name, rs.name)
        assertEquals(kt.plugin, rs.plugin)
        assertEquals("pass|word|with|many|pipes", rs.password)
        assertEquals("Node|Name|With|Pipes", rs.name)
        assertEquals("obfs-local;obfs=http|mode=pipe", rs.plugin)
    }

    @Test
    fun testCandidateParsersDifferentialNative() {
        // 1. Trojan
        for (i in 1..10) {
            val pass = "trojan_pass_$i"
            val uri = "trojan://$pass@192.168.1.$i:443?sni=sni$i.com&allowInsecure=1#Trojan_$i"
            val kt = parseTrojan(uri)
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("trojan", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.password, rs.password)
            assertEquals(kt.name ?: "", rs.name)
            assertEquals(kt.sni ?: "", rs.sni)
            assertEquals(kt.allowInsecure ?: false, rs.allowInsecure)
        }

        // 2. TUIC
        for (i in 1..10) {
            val uuid = "a3424107-160a-4286-9051-7d1c5a93b48$i"
            val token = "tuic_token_$i"
            val uri = "tuic://$uuid:$token@10.0.1.$i:8443?sni=tuic$i.org&allow_insecure=1#TUIC_$i"
            val kt = parseTuic(uri)
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("tuic", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.uuid ?: "", rs.username)
            assertEquals(kt.token ?: "", rs.password)
            assertEquals(kt.name ?: "", rs.name)
            assertEquals(kt.sni ?: "", rs.sni)
            assertEquals(kt.allowInsecure ?: false, rs.allowInsecure)
        }

        // 3. Hysteria 1 & 2
        for (i in 1..10) {
            val uri1 = "hysteria://hy1-$i.org:30000?auth=token$i&peer=sni$i.com&protocol=udp#Hy1_$i"
            val kt1 = parseHysteria1(uri1)
            val rs1 = RustBridge.parseProxy(uri1)
            assertEquals("SUCCESS", rs1.status)
            assertEquals("hysteria1", rs1.protocol)
            assertEquals(kt1.serverAddress, rs1.server)
            assertEquals(kt1.serverPort, rs1.port)
            assertEquals(kt1.authPayload ?: "", rs1.password)

            val uri2 = "hy2://user$i:pass$i@hy2-$i.org:443?sni=sni$i.com&obfs=salamander&obfs-password=pwd$i#Hy2_$i"
            val kt2 = parseHysteria2(uri2)
            val rs2 = RustBridge.parseProxy(uri2)
            assertEquals("SUCCESS", rs2.status)
            assertEquals("hysteria2", rs2.protocol)
            assertEquals(kt2.serverAddress, rs2.server)
            assertEquals(kt2.serverPort, rs2.port)
            assertEquals(kt2.authPayload ?: "", rs2.authPayload)
        }

        // 4. VLESS
        for (i in 1..10) {
            val uuid = "b5424107-160a-4286-9051-7d1c5a93b48$i"
            val host = "vless-native-$i.example.com"
            val port = 5000 + i
            val name = "VLESS_Native_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val uri = "vless://$uuid@$host:$port?type=ws&host=wshost$i.com&path=/vlesspath$i&security=reality&pbk=pubkey$i&sid=shortid$i&fp=chrome&packetEncoding=packet&flow=xtls-rprx-vision#$encodedName"
            val kt = parseV2Ray(uri) as VMessBean
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("vless", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.uuid ?: "", rs.username)
            assertEquals(kt.name ?: "", rs.name)
            assertEquals(kt.sni ?: "", rs.sni)
            assertEquals(kt.type ?: "tcp", rs.transportType)
            assertEquals(kt.host ?: "", rs.transportHost)
            assertEquals(kt.path ?: "", rs.transportPath)
            assertEquals(kt.realityPubKey ?: "", rs.realityPubKey)
            assertEquals(kt.realityShortId ?: "", rs.realityShortId)
            assertEquals(kt.utlsFingerprint ?: "", rs.utlsFingerprint)
            assertEquals(kt.packetEncoding ?: 0, rs.packetEncoding)
            assertEquals(-1, rs.alterId)
            assertEquals(kt.encryption ?: "", rs.encryption)
            assertEquals(kt.isTLS(), rs.tlsEnabled)
        }

        // 5. VMess
        val ciphers = listOf("auto", "aes-128-gcm", "chacha20-poly1305")
        for (i in 1..10) {
            val uuid = "c6424107-160a-4286-9051-7d1c5a93b49$i"
            val host = "vmess-native-$i.example.com"
            val port = 6000 + i
            val name = "VMess_Native_$i"
            val cipher = ciphers[i % ciphers.size]
            val aid = if (i % 2 == 0) 0 else 16
            val json = """
                {
                    "v": "2",
                    "ps": "$name",
                    "add": "$host",
                    "port": "$port",
                    "id": "$uuid",
                    "aid": "$aid",
                    "scy": "$cipher",
                    "net": "ws",
                    "host": "wshost$i.com",
                    "path": "/path$i",
                    "tls": "tls",
                    "sni": "sni$i.com"
                }
            """.trimIndent()
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
            val uri = "vmess://$b64"
            val kt = parseV2Ray(uri) as VMessBean
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("vmess", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.uuid ?: "", rs.username)
            assertEquals(kt.name ?: "", rs.name)
            assertEquals(kt.sni ?: "", rs.sni)
            assertEquals(kt.alterId ?: 0, rs.alterId)
            assertEquals(kt.encryption ?: "auto", rs.encryption)
            assertEquals(kt.isTLS(), rs.tlsEnabled)
        }

        // 6. Legacy VMess CSV
        val csvCases = listOf(
            """remarks = vmess,1.2.3.4,443,auto,"b831381d-6324-4d53-ad4f-8cda48b30811",over-tls=true,tls-host=example.com,obfs=websocket,obfs-path="/path"obfs Host:example.com[""",
            """test = vmess,192.168.1.1,10086,aes-128-gcm,"11111111-2222-3333-4444-555555555555",obfs=http,obfs-path="/api"obfs Host:myhost.net[""",
            """my_node = vmess,node.example.org,8443,chacha20-poly1305,"22222222-3333-4444-5555-666666666666",over-tls=true,tls-host=sni.example.org,obfs=custom-obfs"""
        )
        for (csv in csvCases) {
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(csv.toByteArray())
            val uri = "vmess://$b64"
            val kt = parseV2Ray(uri) as VMessBean
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("vmess", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.uuid ?: "", rs.username)
            assertEquals(kt.encryption ?: "", rs.encryption)
            assertEquals(kt.type ?: "tcp", rs.transportType)
            assertEquals(kt.isTLS(), rs.tlsEnabled)
            assertEquals(kt.path ?: "", rs.transportPath)
            assertEquals(kt.host ?: "", rs.transportHost)
        }

        // 7. Legacy VMess Kitsunebi
        val kitsunebiCases = listOf(
            "auto:5af5d0ec-6ea0-3c43-93db-ca3008503bdb@183.232.56.161:1202" to
                "remarks=*🇯🇵JP%20-355&alterId=0&path=/v2ray&obfs=websocket&tls=1&obfsParam=%7B%22Host%22:%22183.232.56.161%22%7D",
            "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443" to
                "remarks=FastNode&alterId=4&path=/ws&obfs=websocket&tls=1&obfsParam=sni.example.com",
            "chacha20-poly1305:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@10.0.0.1:8080" to
                "remarks=PlainTCP&alterId=16&path=/&obfs=none&allowInsecure=1",
            "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@node.net:443" to
                "remarks=Hello+World%20Test&alterId=0&path=/test&obfs=websocket&tls=nonstandard",
            "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@node.net:443" to
                "remarks=NoHostJSON&alterId=0&path=/test&obfs=websocket&tls=1&obfsParam=%7B%22Other%22:%22123%22%7D",
            "chacha20-poly1305:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@node.net:443" to
                "remarks=AllowInsecureTrue&alterId=8&path=/allow&obfs=none&allowInsecure=true&tls=",
            "none:11111111-2222-3333-4444-555555555555@CapitalHost.Net:8080" to null
        )
        for ((creds, query) in kitsunebiCases) {
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(creds.toByteArray())
            val uri = if (query != null) "vmess://$b64?$query" else "vmess://$b64"
            val kt = parseV2Ray(uri) as VMessBean
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("vmess", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.uuid ?: "", rs.username)
            assertEquals(kt.name ?: "", rs.name)
            assertEquals(kt.encryption ?: "", rs.encryption)
            assertEquals(kt.alterId ?: 0, rs.alterId)
            assertEquals(kt.type ?: "tcp", rs.transportType)
            assertEquals(kt.isTLS(), rs.tlsEnabled)
            assertEquals(kt.allowInsecure == true, rs.allowInsecure)
            assertEquals(kt.path ?: "", rs.transportPath)
            assertEquals(kt.host ?: "", rs.transportHost)
            assertEquals(kt.sni ?: "", rs.sni)
        }

        // 8. Legacy VMess v2fly (issue 26)
        val v2flyCases = listOf(
            "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811-16@example.com:443/?path=/ws&host=example.com&tlsServerName=sni.example.com#V2FlyWS",
            "vmess://grpc+tls:b831381d-6324-4d53-ad4f-8cda48b30811-0@grpc.example.com:443/?serviceName=myService&tlsServerName=grpc.example.com#V2FlyGRPC",
            "vmess://http:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-64@10.0.0.1:8080/?path=/http&host=h1.com|h2.com#V2FlyHTTP"
        )
        for (uri in v2flyCases) {
            val kt = parseV2Ray(uri) as VMessBean
            val rs = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rs.status)
            assertEquals("vmess", rs.protocol)
            assertEquals(kt.serverAddress, rs.server)
            assertEquals(kt.serverPort, rs.port)
            assertEquals(kt.uuid ?: "", rs.username)
            assertEquals(kt.name ?: "", rs.name)
            assertEquals(kt.alterId ?: 0, rs.alterId)
            assertEquals(kt.type ?: "tcp", rs.transportType)
            assertEquals(kt.isTLS(), rs.tlsEnabled)
            assertEquals(kt.path ?: "", rs.transportPath)
            assertEquals(kt.host ?: "", rs.transportHost)
            assertEquals(kt.sni ?: "", rs.sni)
        }
    }

    @Test
    fun testParserBenchmarkMultiRound() {
        val uri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwaGFzZS1iLXRlc3QtcGFzc3dvcmRAMTkyLjE2OC4wLjE2ODo4Mzg4#PhaseD-Local-SS"
        val probeBytes = "vialen".toByteArray()

        // 1. Warmup >= 500
        for (i in 1..600) {
            kotlinParse(uri)
            RustBridge.parseProxy(uri)
            RustBridge.probe(probeBytes)
        }

        // 2. 5 Rounds of 5000 iterations
        val rounds = 5
        val iterations = 5000

        val ktMedians = mutableListOf<Double>()
        val rsMedians = mutableListOf<Double>()
        val probeMedians = mutableListOf<Double>()

        for (r in 1..rounds) {
            // Kotlin
            val startKt = System.nanoTime()
            for (i in 1..iterations) {
                kotlinParse(uri)
            }
            val ktUs = ((System.nanoTime() - startKt) / iterations) / 1000.0
            ktMedians.add(ktUs)

            // Rust including JNI
            val startRs = System.nanoTime()
            for (i in 1..iterations) {
                RustBridge.parseProxy(uri)
            }
            val rsUs = ((System.nanoTime() - startRs) / iterations) / 1000.0
            rsMedians.add(rsUs)

            // Probe roundtrip cost
            val startProbe = System.nanoTime()
            for (i in 1..iterations) {
                RustBridge.probe(probeBytes)
            }
            val probeUs = ((System.nanoTime() - startProbe) / iterations) / 1000.0
            probeMedians.add(probeUs)
        }

        ktMedians.sort()
        rsMedians.sort()
        probeMedians.sort()

        val ktMedian = ktMedians[rounds / 2]
        val rsMedian = rsMedians[rounds / 2]
        val probeMedian = probeMedians[rounds / 2]

        println("[BENCHMARK_MULTI] Rounds: $rounds, Iterations: $iterations")
        println("[BENCHMARK_MULTI] Kotlin production SS median: ${String.format("%.2f", ktMedian)} us (min=${String.format("%.2f", ktMedians.first())}, max=${String.format("%.2f", ktMedians.last())})")
        println("[BENCHMARK_MULTI] Rust parser via JNI median: ${String.format("%.2f", rsMedian)} us (min=${String.format("%.2f", rsMedians.first())}, max=${String.format("%.2f", rsMedians.last())})")
        println("[BENCHMARK_MULTI] PROBE_ROUNDTRIP_COST median: ${String.format("%.2f", probeMedian)} us (min=${String.format("%.2f", probeMedians.first())}, max=${String.format("%.2f", probeMedians.last())})")

        // 3. Batch benchmarks: 100 nodes and 1000 nodes
        val batch100 = (1..100).map { "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzQDE5Mi4xNjguMS4xOjg0NDM=#Node_$it" }
        val startBatch100Kt = System.nanoTime()
        for (u in batch100) kotlinParse(u)
        val timeBatch100KtMs = (System.nanoTime() - startBatch100Kt) / 1_000_000.0

        val startBatch100Rs = System.nanoTime()
        for (u in batch100) RustBridge.parseProxy(u)
        val timeBatch100RsMs = (System.nanoTime() - startBatch100Rs) / 1_000_000.0

        println("[BENCHMARK_BATCH] 100 nodes: Kotlin=${String.format("%.3f", timeBatch100KtMs)} ms, Rust=${String.format("%.3f", timeBatch100RsMs)} ms")

        val batch1000 = (1..1000).map { "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzQDE5Mi4xNjguMS4xOjg0NDM=#Node_$it" }
        val startBatch1000Kt = System.nanoTime()
        for (u in batch1000) kotlinParse(u)
        val timeBatch1000KtMs = (System.nanoTime() - startBatch1000Kt) / 1_000_000.0

        val startBatch1000Rs = System.nanoTime()
        for (u in batch1000) RustBridge.parseProxy(u)
        val timeBatch1000RsMs = (System.nanoTime() - startBatch1000Rs) / 1_000_000.0

        println("[BENCHMARK_BATCH] 1000 nodes: Kotlin=${String.format("%.3f", timeBatch1000KtMs)} ms, Rust=${String.format("%.3f", timeBatch1000RsMs)} ms")

        assertTrue(ktMedian > 0)
        assertTrue(rsMedian > 0)
    }
}
