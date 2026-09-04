package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.rust.RustBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class NextParsersDifferentialTest {

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
            val kt = parseTuic(uri)
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
    // 4. REAL DIFFERENTIAL ERROR SEMANTICS
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
            "hy2://user:pass@1.1.1.1:abc"
        )

        for (uri in invalidUris) {
            val ktFailed = try {
                when {
                    uri.startsWith("trojan://") -> { parseTrojan(uri); false }
                    uri.startsWith("tuic://") -> { parseTuic(uri); false }
                    uri.startsWith("hysteria://") -> { parseHysteria1(uri); false }
                    uri.startsWith("hysteria2://") || uri.startsWith("hy2://") -> { parseHysteria2(uri); false }
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
}
