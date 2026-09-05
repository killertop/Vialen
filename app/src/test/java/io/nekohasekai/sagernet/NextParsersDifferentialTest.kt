package io.nekohasekai.sagernet

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.v2ray.parseV2RayN
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.rust.RustBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import java.net.URLEncoder
import java.util.Base64 as JavaBase64

@RunWith(BlockJUnit4ClassRunner::class)
class NextParsersDifferentialTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setupClass() {
            mockkStatic(Base64::class)
            every { Base64.encode(any(), any()) } answers {
                JavaBase64.getEncoder().encode(firstArg<ByteArray>())
            }
            every { Base64.encodeToString(any(), any()) } answers {
                JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
            }
            every { Base64.decode(any<String>(), any()) } answers {
                val raw = firstArg<String>().replace("-", "+").replace("_", "/")
                val pad = (4 - raw.length % 4) % 4
                JavaBase64.getDecoder().decode(raw + "=".repeat(if (pad == 4) 0 else pad))
            }
            every { Base64.decode(any<ByteArray>(), any()) } answers {
                JavaBase64.getDecoder().decode(firstArg<ByteArray>())
            }

            mockkStatic(TextUtils::class)
            every { TextUtils.isEmpty(any()) } answers {
                firstArg<CharSequence?>().isNullOrEmpty()
            }

            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
        }
    }

    @Before
    fun setup() {
        mockkObject(Logs)
        every { Logs.d(any()) } answers {}
        every { Logs.d(any(), any()) } answers {}
        every { Logs.i(any()) } answers {}
        every { Logs.i(any(), any()) } answers {}
        every { Logs.w(any<String>()) } answers {}
        every { Logs.w(any<Throwable>()) } answers {}
        every { Logs.w(any(), any()) } answers {}
        every { Logs.e(any<String>()) } answers {}
        every { Logs.e(any<Throwable>()) } answers {}
        every { Logs.e(any(), any()) } answers {}
    }

    // ==========================================
    // 1. TROJAN DIFFERENTIAL
    // ==========================================
    @Test
    fun testTrojanDifferential() {
        val testCases = mutableListOf<String>()

        for (i in 1..20) {
            val pass = "trojan_pass_$i"
            val host = when (i % 3) {
                0 -> "192.168.1.$i"
                1 -> "trojan-node-$i.example.com"
                else -> "[2001:db8::$i]"
            }
            val port = 400 + i
            val name = "Trojan_节点_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val sni = "sni$i.example.com"
            val allowInsecure = if (i % 2 == 0) "1" else "0"

            testCases.add("trojan://$pass@$host:$port?sni=$sni&allowInsecure=$allowInsecure#$encodedName")
            testCases.add("trojan://$pass@$host:$port?peer=$sni#$encodedName")
            testCases.add("trojan://$pass@$host:$port#$encodedName")
            testCases.add("trojan://$pass@$host:$port/raw/url/path$i?type=ws&host=wshost$i.com&path=/wspath$i&ed=2048&eh=Sec-WebSocket-Protocol#$encodedName")
            testCases.add("trojan://$pass@$host:$port?type=grpc&serviceName=myGrpcService$i#$encodedName")
            testCases.add("trojan://$pass@$host:$port?security=reality&pbk=pubkey$i&sid=shortid$i&fp=chrome&cert=ca_cert$i&packetEncoding=xudp#$encodedName")
            testCases.add("trojan://$pass@$host:$port?packetEncoding=packet&alpn=h2,http/1.1#$encodedName")
        }

        var matchCount = 0
        for (uri in testCases) {
            val kt = parseTrojan(uri)
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "trojan", rs.protocol)
            assertEquals("Server mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("Port mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("Password mismatch on $uri", kt.password, rs.password)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
            assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
            assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
            assertEquals("TransportHost mismatch on $uri", kt.host ?: "", rs.transportHost)
            assertEquals("TransportPath mismatch on $uri", kt.path ?: "", rs.transportPath)
            assertEquals("Alpn mismatch on $uri", kt.alpn ?: "", rs.alpn)
            assertEquals("Certificates mismatch on $uri", kt.certificates ?: "", rs.certificates)
            assertEquals("RealityPubKey mismatch on $uri", kt.realityPubKey ?: "", rs.realityPubKey)
            assertEquals("RealityShortId mismatch on $uri", kt.realityShortId ?: "", rs.realityShortId)
            assertEquals("EarlyDataHeaderName mismatch on $uri", kt.earlyDataHeaderName ?: "", rs.earlyDataHeaderName)
            assertEquals("WsMaxEarlyData mismatch on $uri", kt.wsMaxEarlyData ?: 0, rs.wsMaxEarlyData)
            assertEquals("PacketEncoding mismatch on $uri", kt.packetEncoding ?: 0, rs.packetEncoding)
            assertEquals("UtlsFingerprint mismatch on $uri", kt.utlsFingerprint ?: "", rs.utlsFingerprint)
            matchCount++
        }
        assertEquals(140, matchCount)
    }

    // ==========================================
    // 2. TUIC DIFFERENTIAL
    // ==========================================
    @Test
    fun testTuicDifferential() {
        val testCases = mutableListOf<String>()

        for (i in 1..20) {
            val uuid = "a3424107-160a-4286-9051-7d1c5a93b48$i"
            val token = "tuic_token_$i"
            val host = when (i % 3) {
                0 -> "10.0.1.$i"
                1 -> "tuic-$i.example.org"
                else -> "[2001:db8::1$i]"
            }
            val port = 8000 + i
            val name = "TUIC_节点_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val sni = "tuic-sni-$i.org"
            val cc = if (i % 2 == 0) "bbr" else "cubic"
            val mode = if (i % 2 == 0) "quic" else "native"
            val allowInsecure = if (i % 2 == 0) "1" else "0"
            val disableSni = if (i % 2 == 0) "1" else "0"

            testCases.add("tuic://$uuid:$token@$host:$port?sni=$sni&congestion_control=$cc&udp_relay_mode=$mode&allow_insecure=$allowInsecure&disable_sni=$disableSni#$encodedName")
            testCases.add("tuic://$uuid:$token@$host:$port#$encodedName")
            testCases.add("tuic://$uuid@$host:$port#$encodedName")
        }

        var matchCount = 0
        for (uri in testCases) {
            val kt = parseTuic(uri).apply { initializeDefaultValues() }
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "tuic", rs.protocol)
            assertEquals("Server mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("Port mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $uri", kt.uuid ?: "", rs.username)
            assertEquals("Token mismatch on $uri", kt.token ?: "", rs.password)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
            assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
            assertEquals("DisableSNI mismatch on $uri", kt.disableSNI ?: false, rs.disableSNI)
            assertEquals("CongestionControl mismatch on $uri", kt.congestionController ?: "", rs.congestionControl)
            assertEquals("UdpRelayMode mismatch on $uri", kt.udpRelayMode ?: "", rs.udpRelayMode)
            matchCount++
        }
        assertEquals(60, matchCount)
    }

    // ==========================================
    // 3. HYSTERIA 1 & 2 DIFFERENTIAL
    // ==========================================
    @Test
    fun testHysteriaDifferential() {
        val testCasesHy1 = mutableListOf<String>()
        val testCasesHy2 = mutableListOf<String>()

        for (i in 1..20) {
            val host = if (i % 2 == 0) "hy1-$i.example.com" else "[2001:db8::2$i]"
            val port = 30000 + i
            val auth = "hy1_auth_token_$i"
            val sni = "sni-hy1-$i.com"
            val name = "Hy1_Node_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val obfs = "obfsParamSecret$i"
            testCasesHy1.add("hysteria://$host:$port?auth=$auth&peer=$sni&upmbps=100&downmbps=200&protocol=udp&obfsParam=$obfs#$encodedName")
            testCasesHy1.add("hysteria://$host:$port?auth=$auth&protocol=udp&mport=30000-30010#$encodedName")
            testCasesHy1.add("hysteria://$host:$port?protocol=udp#$encodedName") // tests default up/down mbps
        }

        for (i in 1..20) {
            val host = if (i % 2 == 0) "hy2-$i.example.com" else "[2001:db8::3$i]"
            val port = 40000 + i
            val user = "hy2user$i"
            val pass = "hy2pass$i"
            val sni = "sni-hy2-$i.com"
            val name = "Hy2_Node_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val obfs = if (i % 2 == 0) "salamander" else "gecko"

            testCasesHy2.add("hysteria2://$user:$pass@$host:$port?sni=$sni&obfs=$obfs&obfs-password=pwd$i&mport=40000-40010#$encodedName")
            testCasesHy2.add("hy2://$user:$pass@$host:$port#$encodedName")
            testCasesHy2.add("hy2://$user@$host:$port#$encodedName")
        }

        var matchCountHy1 = 0
        for (uri in testCasesHy1) {
            val kt = parseHysteria1(uri)
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "hysteria1", rs.protocol)
            assertEquals("Server mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("Port mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("AuthPayload mismatch on $uri", kt.authPayload ?: "", rs.authPayload)
            assertEquals("ServerPorts mismatch on $uri", kt.serverPorts ?: "", rs.serverPorts)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
            assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
            assertEquals("UploadMbps mismatch on $uri", kt.uploadMbps, rs.uploadMbps)
            assertEquals("DownloadMbps mismatch on $uri", kt.downloadMbps, rs.downloadMbps)
            assertEquals("ObfsType mismatch on $uri", kt.obfsType ?: "salamander", rs.obfsType)
            assertEquals("ObfsPassword mismatch on $uri", kt.obfuscation ?: "", rs.obfsPassword)
            assertEquals("DisableChromeParrot mismatch on $uri", kt.disableChromeParrot ?: false, rs.disableChromeParrot)
            assertEquals("BbrProfile mismatch on $uri", kt.bbrProfile ?: "", rs.bbrProfile)
            assertEquals("HopIntervalMax mismatch on $uri", kt.hopIntervalMax ?: 0, rs.hopIntervalMax)
            assertEquals("ObfsMinPacketSize mismatch on $uri", kt.obfsMinPacketSize ?: 512, rs.obfsMinPacketSize)
            assertEquals("ObfsMaxPacketSize mismatch on $uri", kt.obfsMaxPacketSize ?: 1200, rs.obfsMaxPacketSize)
            matchCountHy1++
        }
        assertEquals(60, matchCountHy1)

        var matchCountHy2 = 0
        for (uri in testCasesHy2) {
            val kt = parseHysteria2(uri)
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "hysteria2", rs.protocol)
            assertEquals("Server mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("Port mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("AuthPayload mismatch on $uri", kt.authPayload ?: "", rs.authPayload)
            assertEquals("ServerPorts mismatch on $uri", kt.serverPorts ?: "", rs.serverPorts)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
            assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
            assertEquals("ObfsType mismatch on $uri", kt.obfsType ?: "", rs.obfsType)
            assertEquals("ObfsPassword mismatch on $uri", kt.obfuscation ?: "", rs.obfsPassword)
            assertEquals("UploadMbps mismatch on $uri", kt.uploadMbps ?: 0, rs.uploadMbps)
            assertEquals("DownloadMbps mismatch on $uri", kt.downloadMbps ?: 0, rs.downloadMbps)
            assertEquals("DisableChromeParrot mismatch on $uri", kt.disableChromeParrot ?: false, rs.disableChromeParrot)
            assertEquals("BbrProfile mismatch on $uri", kt.bbrProfile ?: "", rs.bbrProfile)
            assertEquals("HopIntervalMax mismatch on $uri", kt.hopIntervalMax ?: 0, rs.hopIntervalMax)
            assertEquals("ObfsMinPacketSize mismatch on $uri", kt.obfsMinPacketSize ?: 512, rs.obfsMinPacketSize)
            assertEquals("ObfsMaxPacketSize mismatch on $uri", kt.obfsMaxPacketSize ?: 1200, rs.obfsMaxPacketSize)
            matchCountHy2++
        }
        assertEquals(60, matchCountHy2)
    }

    // ==========================================
    // 4. VLESS DIFFERENTIAL
    // ==========================================
    @Test
    fun testOtherCandidatesFirstQueryValueDifferential() {
        data class Case(val prefix: String, val key: String, val readSni: (String) -> String?)
        val cases = listOf(
            Case("trojan://secret@example.com:443", "sni", { parseTrojan(it).sni }),
            Case("tuic://user:secret@example.com:443", "sni", { parseTuic(it).sni }),
            Case("hysteria://example.com:443", "peer", { parseHysteria1(it).sni }),
            Case("hysteria2://secret@example.com:443", "sni", { parseHysteria2(it).sni }),
        )
        cases.forEach { case ->
            val encodedKey = "%${case.key.first().code.toString(16)}${case.key.drop(1)}"
            for (query in listOf(
                "${case.key}=first.example&${case.key}=second.example",
                "$encodedKey=first.example&${case.key}=second.example",
                "${case.key}&${case.key}=second.example",
                "${case.key}=&${case.key}=second.example",
            )) {
                val uri = "${case.prefix}?$query#Query"
                val rust = RustBridge.parseProxy(uri)
                assertEquals("SUCCESS", rust.status)
                assertEquals("SNI: $uri", case.readSni(uri) ?: "", rust.sni)
            }
        }
    }

    @Test
    fun testV2RayRepeatedQueryFirstValueDifferential() {
        for (scheme in listOf("vless", "vmess")) {
            for (query in listOf(
                "security=tls&sni=first.example&sni=second.example",
                "security=tls&%73ni=first.example&sni=second.example",
                "type=ws&type=grpc&path=%2Ffirst&path=%2Fsecond",
                "security=tls&sni&sni=second.example",
            )) {
                val uri = "$scheme://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?$query#Query"
                val kotlin = parseV2Ray(uri)
                val rust = RustBridge.parseProxy(uri)
                assertEquals("SUCCESS", rust.status)
                assertEquals("$scheme $query sni", kotlin.sni ?: "", rust.sni)
                assertEquals("$scheme $query type", kotlin.type ?: "", rust.transportType)
                assertEquals("$scheme $query path", kotlin.path ?: "", rust.transportPath)
            }
        }
    }

    @Test
    fun testMalformedPercentUnicodeAgainstProductionParser() {
        for (value in listOf("%中", "%a中", "%🔥", "%é", "%GG", "%2")) {
            val uri = "trojan://password@example.com:443?type=ws&path=$value#$value"
            val kotlin = parseTrojan(uri)
            val rust = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rust.status)
            assertEquals(kotlin.path ?: "", rust.transportPath)
            assertEquals(kotlin.name ?: "", rust.name)
        }
    }

    @Test
    fun testV2RayUrlPathSegmentsDifferential() {
        for (scheme in listOf("vless", "vmess")) {
            for (path in listOf("/a%20b/trailing/", "//nested/", "/%E8%8A%82%E7%82%B9/", "/plus+literal/")) {
                val uri = "$scheme://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443$path?type=tcp#Path"
                val kotlin = parseV2Ray(uri)
                val rust = RustBridge.parseProxy(uri)
                assertEquals("SUCCESS", rust.status)
                assertEquals("path mismatch: $scheme $path", kotlin.path ?: "", rust.transportPath)
            }
        }
    }

    @Test
    fun testVlessDifferential() {
        val testCases = mutableListOf<String>()

        for (i in 1..25) {
            val uuid = "b831381d-6324-4d53-ad4f-8cda48b308$i"
            val host = when (i % 3) {
                0 -> "192.168.2.$i"
                1 -> "vless-node-$i.example.com"
                else -> "[2001:db8::beef:$i]"
            }
            val port = 5000 + i
            val name = "VLESS_节点_香港_$i 🚀"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val sni = "vless-sni$i.example.com"
            val allowInsecure = if (i % 2 == 0) "1" else "0"

            // Case A: Reality + TCP + Flow Vision
            testCases.add("vless://$uuid@$host:$port?encryption=none&security=reality&sni=$sni&pbk=dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df$i&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision&fp=chrome#$encodedName")
            // Case B: Reality + Flow Vision with -udp443 suffix (must be stripped in parity)
            testCases.add("vless://$uuid@$host:$port?security=reality&sni=$sni&pbk=dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df$i&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision-udp443&fp=firefox#$encodedName")
            // Case C: WS + TLS + EarlyData
            testCases.add("vless://$uuid@$host:$port/raw/vless/path$i?type=ws&security=tls&host=wshost$i.com&path=/wspath$i&sni=$sni&alpn=h2,http/1.1&ed=2048&eh=Sec-WebSocket-Protocol&fp=safari&allowInsecure=$allowInsecure#$encodedName")
            // Case D: gRPC + TLS
            testCases.add("vless://$uuid@$host:$port?type=grpc&security=tls&serviceName=myVlessGrpc$i&sni=$sni&packetEncoding=xudp#$encodedName")
            // Case E: Plain TCP without TLS
            testCases.add("vless://$uuid@$host:$port?type=tcp&packetEncoding=packet#$encodedName")
        }

        var matchCount = 0
        for (uri in testCases) {
            val kt = parseV2Ray(uri) as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "vless", rs.protocol)
            assertEquals("Server mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("Port mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $uri", kt.uuid ?: "", rs.username)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
            assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
            assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
            assertEquals("TransportHost mismatch on $uri", kt.host ?: "", rs.transportHost)
            assertEquals("TransportPath mismatch on $uri", kt.path ?: "", rs.transportPath)
            assertEquals("Alpn mismatch on $uri", kt.alpn ?: "", rs.alpn)
            assertEquals("Certificates mismatch on $uri", kt.certificates ?: "", rs.certificates)
            assertEquals("RealityPubKey mismatch on $uri", kt.realityPubKey ?: "", rs.realityPubKey)
            assertEquals("RealityShortId mismatch on $uri", kt.realityShortId ?: "", rs.realityShortId)
            assertEquals("EarlyDataHeaderName mismatch on $uri", kt.earlyDataHeaderName ?: "", rs.earlyDataHeaderName)
            assertEquals("WsMaxEarlyData mismatch on $uri", kt.wsMaxEarlyData ?: 0, rs.wsMaxEarlyData)
            assertEquals("PacketEncoding mismatch on $uri", kt.packetEncoding ?: 0, rs.packetEncoding)
            assertEquals("UtlsFingerprint mismatch on $uri", kt.utlsFingerprint ?: "", rs.utlsFingerprint)
            assertEquals("AlterId mismatch on $uri", -1, rs.alterId)
            assertEquals("Flow/Encryption mismatch on $uri", kt.encryption ?: "", rs.encryption)
            assertEquals("TLSEnabled mismatch on $uri", kt.isTLS(), rs.tlsEnabled)
            matchCount++
        }
        assertEquals(125, matchCount)
    }

    // ==========================================
    // 5. VMESS DIFFERENTIAL
    // ==========================================
    @Test
    fun testVmessDifferential() {
        val testCases = mutableListOf<String>()

        // 1. V2RayN Base64 JSON format (production standard in the wild)
        val ciphers = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "zero", "none")
        val networks = listOf("tcp", "ws", "grpc", "http")
        for (i in 1..25) {
            val uuid = "c9424107-160a-4286-9051-7d1c5a93b4$i"
            val host = when (i % 3) {
                0 -> "192.168.3.$i"
                1 -> "vmess-$i.example.com"
                else -> "2001:db8::vmess:$i"
            }
            val port = 6000 + i
            val name = "VMess_节点_日本_$i ⚡"
            val cipher = ciphers[i % ciphers.size]
            val net = networks[i % networks.size]
            val aid = if (i % 2 == 0) 0 else 16
            val tls = if (i % 3 == 0) "tls" else "none"
            val headerType = if (net == "tcp" && i % 2 == 0) "http" else "none"

            val json = """
                {
                    "v": "2",
                    "ps": "$name",
                    "add": "$host",
                    "port": "$port",
                    "id": "$uuid",
                    "aid": "$aid",
                    "scy": "$cipher",
                    "net": "$net",
                    "type": "$headerType",
                    "host": "wshost$i.com",
                    "path": "/vmesspath$i",
                    "tls": "$tls",
                    "sni": "vmess-sni$i.com",
                    "alpn": "h2,http/1.1",
                    "fp": "chrome"
                }
            """.trimIndent()

            val b64 = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
            testCases.add("vmess://$b64")
        }

        // 2. VMess DuckSoft URL format
        for (i in 1..15) {
            val uuid = "d0424107-160a-4286-9051-7d1c5a93b5$i"
            val host = "vmess-std-$i.example.org"
            val port = 7000 + i
            val name = "VMess_Std_$i"
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val cipher = ciphers[i % ciphers.size]
            testCases.add("vmess://$uuid@$host:$port?type=ws&security=tls&host=stdhost$i.org&path=/stdpath$i&encryption=$cipher&alterId=4#$encodedName")
            testCases.add("vmess://$uuid@$host:$port?type=tcp&encryption=$cipher#$encodedName")
        }

        var matchCount = 0
        for (uri in testCases) {
            val kt = parseV2Ray(uri) as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "vmess", rs.protocol)
            assertEquals("Server mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("Port mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $uri", kt.uuid ?: "", rs.username)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
            assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
            assertEquals("TransportHost mismatch on $uri", kt.host ?: "", rs.transportHost)
            assertEquals("TransportPath mismatch on $uri", kt.path ?: "", rs.transportPath)
            assertEquals("Alpn mismatch on $uri", kt.alpn ?: "", rs.alpn)
            assertEquals("UtlsFingerprint mismatch on $uri", kt.utlsFingerprint ?: "", rs.utlsFingerprint)
            assertEquals("AlterId mismatch on $uri", kt.alterId ?: 0, rs.alterId)
            assertEquals("Encryption mismatch on $uri", kt.encryption ?: "", rs.encryption)
            assertEquals("TLSEnabled mismatch on $uri", kt.isTLS(), rs.tlsEnabled)
            matchCount++
        }
        assertEquals(55, matchCount)
    }

    // ==========================================
    // 6. VMESS V2RAYN DETAILED PARITY & BOUNDARY CASES
    // ==========================================
    @Test
    fun testVmessV2RayN_DetailedParityAndBoundaryCases() {
        val boundaryCases = listOf(
            // IPv4 add
            """{"v":"2","ps":"IPv4Node","add":"192.168.1.1","port":"10443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}""",
            // Unbracketed IPv6 add
            """{"v":"2","ps":"IPv6Unbracketed","add":"2001:db8::1","port":"10443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}""",
            // Bracketed IPv6 add
            """{"v":"2","ps":"IPv6Bracketed","add":"[2001:db8::1]","port":"10443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}""",
            // Uppercase domain add (parity check: serverAddress preserves exact casing)
            """{"v":"2","ps":"DomainUpper","add":"Node-01.EXAMPLE.com","port":"10443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}""",
            // Uppercase hex in IPv6
            """{"v":"2","ps":"IPv6UpperHex","add":"2001:0DB8::1","port":"10443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}""",
            // Extra JSON scalar fields
            """{"v":"2","ps":"ExtraScalars","add":"10.0.0.1","port":"8443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws","extra1":"val1","extra2":123}""",
            // Nested extra JSON object
            """{"v":"2","ps":"NestedObj","add":"10.0.0.2","port":"8443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws","nested":{"k":"v","sub":42}}""",
            // Nested extra JSON array
            """{"v":"2","ps":"NestedArr","add":"10.0.0.3","port":"8443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws","tags":["asia","fast",3]}""",
            // Unquoted numeric port and aid
            """{"v":"2","ps":"NumericPortAid","add":"10.0.0.4","port":8443,"id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":16,"net":"ws"}""",
            // Escaped non-BMP Unicode in JSON string, matching Gson surrogate-pair decoding
            "{\"v\":\"2\",\"ps\":\"Escaped \\uD83D\\uDE80 Node\",\"add\":\"10.0.0.7\",\"port\":\"8443\",\"id\":\"b831381d-6324-4d53-ad4f-8cda48b30811\",\"net\":\"ws\"}",
            // Missing scy (defaults to auto) and missing aid (defaults to 0)
            """{"v":"2","ps":"DefaultsNode","add":"10.0.0.5","port":"8443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}""",
            // HeaderType http with tcp net
            """{"v":"2","ps":"HttpHeaderType","add":"10.0.0.6","port":"8443","id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"tcp","type":"http"}"""
        )

        for (json in boundaryCases) {
            val b64 = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
            val uri = "vmess://$b64"
            val kt = parseV2Ray(uri) as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $json", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $json", "vmess", rs.protocol)
            // Observable serverAddress parity: exact match
            assertEquals("ServerAddress mismatch on $json", kt.serverAddress, rs.server)
            assertEquals("ServerPort mismatch on $json", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $json", kt.uuid ?: "", rs.username)
            assertEquals("Name mismatch on $json", kt.name ?: "", rs.name)
            assertEquals("TransportType mismatch on $json", kt.type ?: "tcp", rs.transportType)
            assertEquals("AlterId mismatch on $json", kt.alterId ?: 0, rs.alterId)
            assertEquals("Encryption mismatch on $json", kt.encryption ?: "", rs.encryption)
            assertEquals("TLSEnabled mismatch on $json", kt.isTLS(), rs.tlsEnabled)
        }
    }

    // ==========================================
    // 7. VMESS V2RAYN INVALID INPUTS & ERROR CLASSIFICATION
    // ==========================================
    @Test
    fun testVmessV2RayN_InvalidInputsDifferential() {
        // Pairs of (JSON, expected Rust status)
        val invalidCases = listOf(
            // 1. Unterminated string in value -> syntax error
            """{"v":"2","add":"1.1.1.1""" to "INVALID_URI",
            // 2. Unterminated string in key -> syntax error
            """{"v:"2","add":"1.1.1.1","port":"1080","id":"u","net":"ws"}""" to "INVALID_URI",
            // 3. Unclosed top-level JSON object -> syntax error
            """{"v":"2","add":"1.1.1.1","port":"1080","id":"u","net":"ws"""" to "INVALID_URI",
            // 4. Trailing garbage after valid JSON -> syntax error
            """{"v":"2","add":"1.1.1.1","port":"1080","id":"u","net":"ws"} trailing""" to "INVALID_URI",
            // 5. Missing required field "add" -> schema error (Kotlin throws "invalid VmessQRCode")
            """{"v":"2","port":"1080","id":"u","net":"ws"}""" to "INVALID_URI",
            // 6. Uppercase "ADD" -> Kotlin Gson doesn't populate add, throws "invalid VmessQRCode"
            """{"v":"2","ADD":"1.1.1.1","port":"1080","id":"u","net":"ws"}""" to "INVALID_URI",
            // 7. Missing required field "port" -> schema error
            """{"v":"2","add":"1.1.1.1","id":"u","net":"ws"}""" to "INVALID_URI",
            // 8. Uppercase "PORT" -> schema error
            """{"v":"2","add":"1.1.1.1","PORT":"1080","id":"u","net":"ws"}""" to "INVALID_URI",
            // 9. Missing required field "id" -> schema error
            """{"v":"2","add":"1.1.1.1","port":"1080","net":"ws"}""" to "INVALID_URI",
            // 10. Uppercase "ID" -> schema error
            """{"v":"2","add":"1.1.1.1","port":"1080","ID":"u","net":"ws"}""" to "INVALID_URI",
            // 11. Missing required field "net" -> schema error
            """{"v":"2","add":"1.1.1.1","port":"1080","id":"u"}""" to "INVALID_URI",
            // 12. Uppercase "NET" -> schema error
            """{"v":"2","add":"1.1.1.1","port":"1080","id":"u","NET":"ws"}""" to "INVALID_URI",
            // 13. Port 0 -> port error
            """{"v":"2","add":"1.1.1.1","port":"0","id":"u","net":"ws"}""" to "INVALID_PORT",
            // 14. Non-numeric port -> port error
            """{"v":"2","add":"1.1.1.1","port":"abc","id":"u","net":"ws"}""" to "INVALID_PORT"
        )

        for ((json, expectedStatus) in invalidCases) {
            val b64 = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
            val uri = "vmess://$b64"

            var ktInvalid = false
            try {
                val bean = parseV2RayN(uri)
                if (bean.serverPort == null || bean.serverPort <= 0) {
                    ktInvalid = true
                }
            } catch (_: Throwable) {
                ktInvalid = true
            }

            val rs = RustBridge.parseProxy(uri)

            assertTrue("Kotlin must reject or produce invalid port for V2RayN JSON: $json", ktInvalid)
            // RawUpdater calls parseV2Ray, not parseV2RayN. A thrown legacy
            // format error can fall through to a valid hostname-only URL.
            val production = runCatching { parseV2Ray(uri) }
            val validEndpoint = production.getOrNull()?.serverPort?.let { it in 1..65535 } == true
            assertEquals("Production entrypoint outcome for $json", validEndpoint, rs.status == "SUCCESS")
            if (validEndpoint) {
                assertEquals(production.getOrThrow().serverAddress, rs.server)
                assertEquals(production.getOrThrow().serverPort, rs.port)
            } else if (expectedStatus == "INVALID_PORT") {
                assertEquals("INVALID_PORT", rs.status)
            }
        }

        // Invalid Base64 payload
        val nonBase64 = "vmess://!!!not-base-64!!!"
        val production = runCatching { parseV2Ray(nonBase64) }
        val rsInvalidB64 = RustBridge.parseProxy(nonBase64)
        assertEquals(production.isSuccess, rsInvalidB64.status == "SUCCESS")
        if (production.isSuccess) assertEquals(production.getOrThrow().serverAddress, rsInvalidB64.server)
    }

    // ==========================================
    // 8. REAL DIFFERENTIAL ERROR SEMANTICS
    // ==========================================
    @Test
    fun testInvalidInputsDifferential() {
        val invalidUris = listOf(
            "trojan://user:pass@1.1.1.1:0",
            "trojan://user:pass@1.1.1.1:99999",
            "trojan://user:pass@1.1.1.1:abc",
            "tuic://uuid:token@1.1.1.1:0",
            "tuic://uuid:token@1.1.1.1:70000",
            "tuic://uuid:token@1.1.1.1:notaport",
            "hysteria://1.1.1.1:0?protocol=udp",
            "hysteria://1.1.1.1:70000?protocol=udp",
            "hysteria://1.1.1.1:30000?protocol=faketcp",
            "hysteria://1.1.1.1:30000?protocol=wechat-video",
            "hysteria2://user:pass@1.1.1.1:0",
            "hysteria2://user:pass@1.1.1.1:99999",
            "hy2://user:pass@1.1.1.1:abc",
            // VLESS invalid URIs
            "vless://uuid@1.1.1.1:0",
            "vless://uuid@1.1.1.1:99999",
            "vless://uuid@1.1.1.1:notaport",
            "vless://uuid@[2001:db8::1]:0",
            // VMess DuckSoft invalid URIs
            "vmess://uuid@1.1.1.1:0",
            "vmess://uuid@1.1.1.1:99999",
            "vmess://uuid@1.1.1.1:notaport"
        )

        for (uri in invalidUris) {
            val ktFailed = try {
                when {
                    uri.startsWith("trojan://") -> { parseTrojan(uri); false }
                    uri.startsWith("tuic://") -> { parseTuic(uri); false }
                    uri.startsWith("hysteria://") -> { parseHysteria1(uri); false }
                    uri.startsWith("hysteria2://") || uri.startsWith("hy2://") -> { parseHysteria2(uri); false }
                    uri.startsWith("vless://") || uri.startsWith("vmess://") -> { parseV2Ray(uri); false }
                    else -> false
                }
            } catch (_: Throwable) {
                true
            }

            val rs = RustBridge.parseProxy(uri)
            val rsFailed = rs.status != "SUCCESS"

            assertTrue("Both Kotlin and Rust must fail for invalid URI: $uri (ktFailed=$ktFailed, rsFailed=$rsFailed)", ktFailed && rsFailed)

            if (uri.contains(":0") || uri.contains(":99999") || uri.contains(":70000") || uri.contains(":abc") || uri.contains(":notaport")) {
                assertEquals("Expected INVALID_PORT for $uri", "INVALID_PORT", rs.status)
            }
        }

        // Test scheme error with plain string (fixed fixture: http://1.1.1.1:80)
        val rsHttp = RustBridge.parseProxy("http://1.1.1.1:80")
        assertEquals("INVALID_SCHEME", rsHttp.status)
    }

    // ==========================================
    // 9. LEGACY VMESS CSV DIFFERENTIAL
    // ==========================================
    @Test
    fun testVmessLegacyCsvDifferential() {
        val csvCases = listOf(
            // Plain CSV with TLS and obfs websocket
            """remarks = vmess,1.2.3.4,443,auto,"b831381d-6324-4d53-ad4f-8cda48b30811",over-tls=true,tls-host=example.com,obfs=websocket,obfs-path="/path"obfs Host:example.com[""",
            // Plain CSV without TLS, custom port
            """test = vmess,192.168.1.1,10086,aes-128-gcm,"11111111-2222-3333-4444-555555555555",obfs=http,obfs-path="/api"obfs Host:myhost.net[""",
            // Minimal CSV
            """server = vmess,10.0.0.1,8080,none,"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"""",
            // Untrimmed fields & verbatim obfs transport type
            """my_node = vmess,node.example.org,8443,chacha20-poly1305,"22222222-3333-4444-5555-666666666666",over-tls=true,tls-host=sni.example.org,obfs=custom-obfs"""
        )

        for (csv in csvCases) {
            val b64 = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(csv.toByteArray())
            val uri = "vmess://$b64"

            val kt = parseV2Ray(uri) as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $csv", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $csv", "vmess", rs.protocol)
            assertEquals("ServerAddress mismatch on $csv", kt.serverAddress, rs.server)
            assertEquals("ServerPort mismatch on $csv", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $csv", kt.uuid ?: "", rs.username)
            assertEquals("Encryption mismatch on $csv", kt.encryption ?: "", rs.encryption)
            assertEquals("TransportType mismatch on $csv", kt.type ?: "tcp", rs.transportType)
            assertEquals("TLSEnabled mismatch on $csv", kt.isTLS(), rs.tlsEnabled)
            assertEquals("Path mismatch on $csv", kt.path ?: "", rs.transportPath)
            assertEquals("Host mismatch on $csv", kt.host ?: "", rs.transportHost)
        }
    }

    // ==========================================
    // 10. LEGACY VMESS V2FLY (ISSUE 26) DIFFERENTIAL
    // ==========================================
    @Test
    fun testVmessLegacyV2FlyDifferential() {
        val v2flyCases = listOf(
            "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811-16@example.com:443/?path=/ws&host=example.com&tlsServerName=sni.example.com#V2FlyWS",
            "vmess://grpc+tls:b831381d-6324-4d53-ad4f-8cda48b30811-0@grpc.example.com:443/?serviceName=myService&tlsServerName=grpc.example.com#V2FlyGRPC",
            "vmess://http:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-64@10.0.0.1:8080/?path=/http&host=h1.com|h2.com#V2FlyHTTP",
            "vmess://httpupgrade+tls:11111111-2222-3333-4444-555555555555-8@upgrade.example.com:8443/?path=/up&host=upgrade.example.com#V2FlyUpgrade"
        )

        for (uri in v2flyCases) {
            val kt = parseV2Ray(uri) as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "vmess", rs.protocol)
            assertEquals("ServerAddress mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("ServerPort mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $uri", kt.uuid ?: "", rs.username)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("AlterId mismatch on $uri", kt.alterId ?: 0, rs.alterId)
            assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
            assertEquals("TLSEnabled mismatch on $uri", kt.isTLS(), rs.tlsEnabled)
            assertEquals("Path mismatch on $uri", kt.path ?: "", rs.transportPath)
            assertEquals("Host mismatch on $uri", kt.host ?: "", rs.transportHost)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
        }
    }

    // ==========================================
    // 12. LEGACY VMESS INVALID / BOUNDARY DIFFERENTIAL
    // ==========================================
    @Test
    fun testLegacyVmessInvalidDifferential() {
        val stdEncoder = JavaBase64.getEncoder()

        // 1. v2fly invalid URLs (directly tested through parseV2Ray)
        val v2flyInvalidCases = listOf(
            // Missing alterId in password (Kotlin parseV2Ray throws NumberFormatException)
            "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443/?path=/ws" to "INVALID_URI",
            // Invalid port 0 (Kotlin parseV2Ray toHttpUrl throws IllegalArgumentException)
            "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811-16@example.com:0/?path=/ws" to "INVALID_PORT",
            // Out of range port 99999 (Kotlin parseV2Ray toHttpUrl throws IllegalArgumentException)
            "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811-16@example.com:99999/?path=/ws" to "INVALID_PORT",
            // Non-numeric port (Kotlin parseV2Ray toHttpUrl throws IllegalArgumentException)
            "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811-16@example.com:notaport/?path=/ws" to "INVALID_PORT"
        )

        for ((uri, expectedStatus) in v2flyInvalidCases) {
            val ktFailed = try {
                parseV2Ray(uri)
                false
            } catch (_: Throwable) {
                true
            }

            val rs = RustBridge.parseProxy(uri)
            val rsFailed = rs.status != "SUCCESS"

            assertTrue("Both Kotlin and Rust must fail for invalid v2fly URI: $uri (ktFailed=$ktFailed, rsFailed=$rsFailed)", ktFailed && rsFailed)
            assertEquals("Expected status $expectedStatus for $uri", expectedStatus, rs.status)
        }

        // 2. CSV invalid payloads tested against parseV2RayN (which directly executes parseCsvVMess)
        val csvInvalidCases = listOf(
            // Less than 5 fields (Kotlin parseCsvVMess throws IndexOutOfBoundsException)
            "vmess://" + stdEncoder.encodeToString("remarks = vmess,1.2.3.4,443,auto".toByteArray()) to "INVALID_URI",
            // Non-numeric port (Kotlin parseCsvVMess throws NumberFormatException)
            "vmess://" + stdEncoder.encodeToString("""remarks = vmess,1.2.3.4,notaport,auto,"b831381d-6324-4d53-ad4f-8cda48b30811"""".toByteArray()) to "INVALID_PORT"
        )

        for ((uri, _) in csvInvalidCases) {
            val ktFailed = try {
                parseV2RayN(uri)
                false
            } catch (_: Throwable) {
                true
            }

            val rs = RustBridge.parseProxy(uri)
            assertTrue("Internal CSV parser must reject malformed CSV", ktFailed)
            val production = runCatching { parseV2Ray(uri) }
            assertEquals("Production CSV fallback: $uri", production.isSuccess, rs.status == "SUCCESS")
            if (production.isSuccess) {
                assertEquals(production.getOrThrow().serverAddress, rs.server)
                assertEquals(production.getOrThrow().serverPort, rs.port)
            }
        }
    }

    // ==========================================
    // 13. URL & QUERY DECODING OKHTTP DIFFERENTIAL
    // ==========================================
    @Test
    fun testUrlAndQueryDecodingOkHttpDifferential() {
        // Focused differential test cases for URL / query decoding parity against OkHttp
        // Testing:
        // 1) Plus sign in query vs space
        // 2) Key-only query parameters (no '='), e.g. ?allowInsecure&tls
        // 3) Empty parameter values, e.g. ?sni=&allowInsecure=
        // 4) Percent-encoded Unicode strings in query values (path, sni, remarks)
        // 5) Bracketed IPv6 hosts (bracket stripping, port extraction, unlowercased hex casing)
        // 6) Server address case preservation (no unsolicited lowercasing)

        val testUris = listOf(
            // Plus in path query param (must decode to space in query parameter)
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@node.example.com:443?type=ws&security=tls&path=/ws+path%20query#Node1",
            // Key-only query parameter (e.g. ?allowInsecure without =1 or =true)
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@node.example.com:443?type=ws&security=tls&allowInsecure#Node2",
            // Empty query parameter values
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@node.example.com:443?type=ws&security=tls&sni=&path=#Node3",
            // Percent-encoded Unicode in query parameters
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@node.example.com:443?type=ws&security=tls&path=/%E4%BD%A0%E5%A5%BD%2B%E4%B8%96%E7%95%8C#Node4",
            // Bracketed IPv6 address with port (preserving uppercase hex)
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@[2001:0DB8:ABCD:0012::1]:8443?type=grpc&security=tls&serviceName=myGrpc#IPv6Node",
            // Bracketed IPv6 address with standard DuckSoft VMess
            "vmess://b831381d-6324-4d53-ad4f-8cda48b30811@[2001:DB8::1]:443?type=ws&security=tls#IPv6VMess",
            // Mixed case domain name (serverAddress casing must be preserved exactly as Kotlin does)
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@MixedCaseHost.Example.Com:443?type=ws#CasedNode",
            // Trojan with plus in SNI and percent-encoded Unicode in path
            "trojan://password123@node.example.com:443?type=ws&sni=sni+test.com&path=/%F0%9F%8C%9F+star#TrojanNode",
            // Trojan with bracketed IPv6
            "trojan://password123@[fe80::1ff:fe23:4567:890a]:443?type=tcp#TrojanIPv6"
        )

        for (uri in testUris) {
            val kt = when {
                uri.startsWith("trojan://") -> parseTrojan(uri)
                uri.startsWith("vless://") || uri.startsWith("vmess://") -> parseV2Ray(uri)
                else -> error("unsupported scheme in test: $uri")
            }
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)

            when (kt) {
                is io.nekohasekai.sagernet.fmt.v2ray.VMessBean -> {
                    assertEquals("ServerAddress mismatch on $uri", kt.serverAddress, rs.server)
                    assertEquals("ServerPort mismatch on $uri", kt.serverPort, rs.port)
                    assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
                    assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
                    assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
                    assertEquals("TransportPath mismatch on $uri", kt.path ?: "", rs.transportPath)
                    assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
                }
                is io.nekohasekai.sagernet.fmt.trojan.TrojanBean -> {
                    assertEquals("ServerAddress mismatch on $uri", kt.serverAddress, rs.server)
                    assertEquals("ServerPort mismatch on $uri", kt.serverPort, rs.port)
                    assertEquals("Password mismatch on $uri", kt.password, rs.password)
                    assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
                    assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
                    assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
                    assertEquals("TransportPath mismatch on $uri", kt.path ?: "", rs.transportPath)
                    assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure ?: false, rs.allowInsecure)
                }
            }
        }
    }

    @Test
    fun testTuicAndHysteriaQueryDecodingOkHttpDifferential() {
        val tuicUri = "tuic://uuid:token@tuic.example.com:443?sni=sni+tuic.example&congestion_control=bbr+plus&udp_relay_mode=quic+mode&alpn=h3+test#TuicPlus"
        val tuicKt = parseTuic(tuicUri)
        val tuicRs = RustBridge.parseProxy(tuicUri)
        assertEquals("SUCCESS", tuicRs.status)
        assertEquals(tuicKt.sni ?: "", tuicRs.sni)
        assertEquals(tuicKt.congestionController ?: "", tuicRs.congestionControl)
        assertEquals(tuicKt.udpRelayMode ?: "", tuicRs.udpRelayMode)
        assertEquals(tuicKt.alpn ?: "", tuicRs.alpn)

        val hy1Uri = "hysteria://hy1.example.com:443?auth=token+abc&peer=sni+hy1.example&insecure=true&alpn=h3+custom&obfsParam=obfs+secret&protocol=udp#Hy1Plus"
        val hy1Kt = parseHysteria1(hy1Uri)
        val hy1Rs = RustBridge.parseProxy(hy1Uri)
        assertEquals("SUCCESS", hy1Rs.status)
        assertEquals(hy1Kt.authPayload ?: "", hy1Rs.authPayload)
        assertEquals(hy1Kt.authPayload ?: "", hy1Rs.password)
        assertEquals(hy1Kt.sni ?: "", hy1Rs.sni)
        assertEquals(hy1Kt.alpn ?: "", hy1Rs.alpn)
        assertEquals(hy1Kt.obfuscation ?: "", hy1Rs.obfsPassword)

        val hy2Uri = "hy2://user:pass@hy2.example.com:443?sni=sni+hy2.example&insecure=true&obfs=salamander&obfs-password=obfs+secret#Hy2Plus"
        val hy2Kt = parseHysteria2(hy2Uri)
        val hy2Rs = RustBridge.parseProxy(hy2Uri)
        assertEquals("SUCCESS", hy2Rs.status)
        assertEquals(hy2Kt.authPayload ?: "", hy2Rs.authPayload)
        assertEquals(hy2Kt.sni ?: "", hy2Rs.sni)
        assertEquals(hy2Kt.allowInsecure ?: false, hy2Rs.allowInsecure)
        assertEquals(hy2Kt.obfsType ?: "", hy2Rs.obfsType)
        assertEquals(hy2Kt.obfuscation ?: "", hy2Rs.obfsPassword)
    }
}
