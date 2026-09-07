package io.nekohasekai.sagernet

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.http.toUri
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.hysteria.toUri
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.tuic.toUri
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.anytls.toUri
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.util.Base64 as JavaBase64

class GoldenProtocolLevel1Test {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
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
    }

    // -------------------------------------------------------------
    // 1. SOCKS
    // -------------------------------------------------------------
    @Test
    fun testSocks5NoAuth() {
        val link = "socks5://192.168.1.100:1080#Socks5NoAuth"
        val bean = parseSOCKS(link).applyDefaultValues()
        assertEquals("192.168.1.100", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertTrue(bean.username.isEmpty())
        assertTrue(bean.password.isEmpty())
        assertEquals("Socks5NoAuth", bean.name)
    }

    @Test
    fun testSocks5WithAuthRoundTrip() {
        val link = "socks5://testuser:testpass@192.168.1.100:1080#SampleSocks5Auth"
        val bean = parseSOCKS(link).applyDefaultValues()
        assertEquals("192.168.1.100", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("testuser", bean.username)
        assertEquals("testpass", bean.password)
        assertEquals(5, bean.protocolVersion())
        assertEquals("SampleSocks5Auth", bean.name)

        val uri = bean.toUri()
        val reparsed = parseSOCKS(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.username, reparsed.username)
        assertEquals(bean.password, reparsed.password)
    }

    // -------------------------------------------------------------
    // 2. HTTP / HTTPS
    // -------------------------------------------------------------
    @Test
    fun testHttpPlain() {
        val link = "http://proxy.example.com:8080#PlainHttp"
        val bean = parseHttp(link).applyDefaultValues()
        assertEquals("proxy.example.com", bean.serverAddress)
        assertEquals(8080, bean.serverPort)
        assertFalse(bean.isTLS())
        assertEquals("PlainHttp", bean.name)
    }

    @Test
    fun testHttpsWithAuthRoundTrip() {
        val link = "https://admin:secret123@proxy.example.com:8443?sni=proxy.example.com#SampleHttps"
        val bean = parseHttp(link).applyDefaultValues()
        assertEquals("proxy.example.com", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("admin", bean.username)
        assertEquals("secret123", bean.password)
        assertEquals("proxy.example.com", bean.sni)
        assertTrue(bean.isTLS())
        assertEquals("SampleHttps", bean.name)

        val uri = bean.toUri()
        val reparsed = parseHttp(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.username, reparsed.username)
        assertEquals(bean.password, reparsed.password)
        assertEquals(bean.isTLS(), reparsed.isTLS())
    }

    // -------------------------------------------------------------
    // 3. Shadowsocks
    // -------------------------------------------------------------
    @Test
    fun testShadowsocksUrlSafeBase64Chacha20() {
        val userinfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:mypassword".toByteArray())
        val link = "ss://$userinfo@ss.example.com:8388#SampleSSChacha"
        val bean = parseShadowsocks(link).applyDefaultValues()
        assertEquals("ss.example.com", bean.serverAddress)
        assertEquals(8388, bean.serverPort)
        assertEquals("mypassword", bean.password)
        assertEquals("chacha20-ietf-poly1305", bean.method)
        assertEquals("SampleSSChacha", bean.name)

        val uri = bean.toUri()
        val reparsed = parseShadowsocks(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.password, reparsed.password)
        assertEquals(bean.method, reparsed.method)
    }

    @Test
    fun testShadowsocksStandardBase64Aes256Gcm() {
        val userinfo = JavaBase64.getEncoder().encodeToString("aes-256-gcm:secretKey999".toByteArray())
        val link = "ss://$userinfo@ss.example.com:8389#SampleSSGcm"
        val bean = parseShadowsocks(link).applyDefaultValues()
        assertEquals("ss.example.com", bean.serverAddress)
        assertEquals(8389, bean.serverPort)
        assertEquals("secretKey999", bean.password)
        assertEquals("aes-256-gcm", bean.method)
        assertEquals("SampleSSGcm", bean.name)
    }

    @Test
    fun testShadowsocksAes128Gcm() {
        val userinfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString("aes-128-gcm:pass128".toByteArray())
        val link = "ss://$userinfo@1.2.3.4:8390#SS128"
        val bean = parseShadowsocks(link).applyDefaultValues()
        assertEquals("1.2.3.4", bean.serverAddress)
        assertEquals("aes-128-gcm", bean.method)
        assertEquals("pass128", bean.password)
    }

    // -------------------------------------------------------------
    // 4. VMess
    // -------------------------------------------------------------
    @Test
    fun testVmessWsTlsRoundTrip() {
        val json = """
            {
                "v": "2",
                "ps": "SampleVMessWS",
                "add": "vmess.example.com",
                "port": "443",
                "id": "a3424107-160a-4286-9051-7d1c5a93b482",
                "aid": "0",
                "scy": "auto",
                "net": "ws",
                "type": "none",
                "host": "vmess.example.com",
                "path": "/websocket",
                "tls": "tls",
                "sni": "vmess.example.com"
            }
        """.trimIndent()
        val b64 = JavaBase64.getEncoder().encodeToString(json.toByteArray())
        val link = "vmess://$b64"
        val bean = (parseV2Ray(link) as VMessBean).applyDefaultValues()
        assertFalse(bean.isVLESS)
        assertEquals("vmess.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("a3424107-160a-4286-9051-7d1c5a93b482", bean.uuid)
        assertEquals("auto", bean.encryption)
        assertEquals("ws", bean.type)
        assertEquals("SampleVMessWS", bean.name)
    }

    @Test
    fun testVmessGrpc() {
        val json = """
            {
                "v": "2",
                "ps": "SampleVMessGRPC",
                "add": "grpc.example.com",
                "port": "443",
                "id": "a3424107-160a-4286-9051-7d1c5a93b482",
                "aid": "0",
                "scy": "auto",
                "net": "grpc",
                "type": "gun",
                "path": "my-grpc-service",
                "tls": "tls"
            }
        """.trimIndent()
        val b64 = JavaBase64.getEncoder().encodeToString(json.toByteArray())
        val link = "vmess://$b64"
        val bean = (parseV2Ray(link) as VMessBean).applyDefaultValues()
        assertEquals("grpc", bean.type)
        assertEquals("my-grpc-service", bean.path)
    }

    // -------------------------------------------------------------
    // 5. VLESS
    // -------------------------------------------------------------
    @Test
    fun testVlessRealityVisionRoundTrip() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@vless.example.com:443?encryption=none&security=reality&sni=yahoo.com&pbk=dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df9&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision#SampleVLESSReality"
        val bean = (parseV2Ray(link) as VMessBean).applyDefaultValues()
        assertTrue(bean.isVLESS)
        assertEquals("vless.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", bean.uuid)
        assertEquals("tls", bean.security)
        assertEquals("yahoo.com", bean.sni)
        assertEquals("xtls-rprx-vision", bean.encryption)
        assertEquals("dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df9", bean.realityPubKey)
        assertEquals("0123456789abcdef", bean.realityShortId)
        assertEquals("SampleVLESSReality", bean.name)

        val uri = bean.toUriVMessVLESSTrojan(false)
        val reparsed = (parseV2Ray(uri) as VMessBean).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.uuid, reparsed.uuid)
        assertEquals(bean.realityPubKey, reparsed.realityPubKey)
    }

    @Test
    fun testVlessWsTls() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@vless-ws.example.com:443?encryption=none&security=tls&sni=vless-ws.example.com&type=ws&path=%2Fvless-ws#VLESS_WS"
        val bean = (parseV2Ray(link) as VMessBean).applyDefaultValues()
        assertTrue(bean.isVLESS)
        assertEquals("ws", bean.type)
        assertEquals("/vless-ws", bean.path)
        assertEquals("tls", bean.security)
    }

    // -------------------------------------------------------------
    // 6. Trojan
    // -------------------------------------------------------------
    @Test
    fun testTrojanTlsTcpRoundTrip() {
        val link = "trojan://trojanpass@trojan.example.com:443?security=tls&sni=trojan.example.com&type=tcp#SampleTrojan"
        val bean = parseTrojan(link).applyDefaultValues()
        assertEquals("trojan.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("trojanpass", bean.password)
        assertEquals("trojan.example.com", bean.sni)
        assertEquals("SampleTrojan", bean.name)

        val uri = bean.toUriVMessVLESSTrojan(true)
        val reparsed = parseTrojan(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.password, reparsed.password)
    }

    @Test
    fun testTrojanWsTls() {
        val link = "trojan://trojanpass@trojan-ws.example.com:443?security=tls&sni=trojan-ws.example.com&type=ws&path=%2Ftrojan-ws#TrojanWS"
        val bean = parseTrojan(link).applyDefaultValues()
        assertEquals("ws", bean.type)
        assertEquals("/trojan-ws", bean.path)
        assertEquals("trojan-ws.example.com", bean.sni)
    }

    // -------------------------------------------------------------
    // 7. Hysteria 1
    // -------------------------------------------------------------
    @Test
    fun testHysteria1UdpValid() {
        val link = "hysteria://hy1.example.com:36712?protocol=udp&auth=myhyauth&upmbps=100&downmbps=200&peer=hy1.example.com#SampleHy1"
        val bean = parseHysteria1(link).applyDefaultValues()
        assertEquals("hy1.example.com", bean.serverAddress)
        assertEquals(100, bean.uploadMbps)
        assertEquals(200, bean.downloadMbps)
        assertEquals(HysteriaBean.PROTOCOL_UDP, bean.protocol)
        assertEquals(1, bean.protocolVersion)

        val uri = bean.toUri()
        val reparsed = parseHysteria1(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.authPayload, reparsed.authPayload)
    }

    @Test
    fun testHysteria1FakeTcpRejected() {
        val link = "hysteria://hy1.example.com:36712?protocol=faketcp&auth=myhyauth#FakeTcpNode"
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteria1(link)
        }
    }

    @Test
    fun testHysteria1WeChatRejected() {
        val link = "hysteria://hy1.example.com:36712?protocol=wechat-video&auth=myhyauth#WeChatNode"
        assertThrows(IllegalArgumentException::class.java) {
            parseHysteria1(link)
        }
    }

    // -------------------------------------------------------------
    // 8. Hysteria 2
    // -------------------------------------------------------------
    @Test
    fun testHysteria2BasicAndObfsRoundTrip() {
        val link = "hysteria2://hy2password@hy2.example.com:443?sni=hy2.example.com&obfs=salamander&obfs-password=obfspass#SampleHy2"
        val bean = parseHysteria2(link).applyDefaultValues()
        assertEquals(2, bean.protocolVersion)
        assertEquals("hy2.example.com", bean.serverAddress)
        assertEquals("hy2password", bean.authPayload)
        assertEquals("obfspass", bean.obfuscation)
        assertEquals("SampleHy2", bean.name)

        val uri = bean.toUri()
        val reparsed = parseHysteria2(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.authPayload, reparsed.authPayload)
        assertEquals(bean.obfuscation, reparsed.obfuscation)
    }

    @Test
    fun testHysteria2PortHopping() {
        val link = "hy2://hy2password@hy2.example.com:443?sni=hy2.example.com&mport=20000-30000#Hy2PortHop"
        val bean = parseHysteria2(link).applyDefaultValues()
        assertEquals("20000-30000", bean.serverPorts)
    }

    // -------------------------------------------------------------
    // 9. TUIC
    // -------------------------------------------------------------
    @Test
    fun testTuicV5RoundTrip() {
        val link = "tuic://b831381d-6324-4d53-ad4f-8cda48b30811:tuicpassword@tuic.example.com:8443?congestion_control=bbr&alpn=h3&sni=tuic.example.com&udp_relay_mode=native#SampleTuic"
        val bean = parseTuic(link).applyDefaultValues()
        assertEquals("tuic.example.com", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", bean.uuid)
        assertEquals("tuicpassword", bean.token)
        assertEquals("bbr", bean.congestionController)
        assertEquals("SampleTuic", bean.name)

        val uri = bean.toUri()
        val reparsed = parseTuic(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.uuid, reparsed.uuid)
        assertEquals(bean.token, reparsed.token)
    }

    // -------------------------------------------------------------
    // 10. WireGuard
    // -------------------------------------------------------------
    @Test
    fun testWireGuardMultiAddressAndMtu() {
        val conf = """
            [Interface]
            PrivateKey = aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            Address = 10.0.0.2/32, fd00::2/128
            DNS = 1.1.1.1
            MTU = 1420

            [Peer]
            PublicKey = bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
            Endpoint = wg.example.com:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 25
        """.trimIndent()
        val beans = RawUpdater.parseWireGuard(conf)
        assertEquals(1, beans.size)
        val bean = beans[0]
        assertEquals("wg.example.com", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", bean.privateKey)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", bean.peerPublicKey)
        assertEquals(1420, bean.mtu)
        assertTrue(bean.localAddress.contains("10.0.0.2/32"))
        assertTrue(bean.localAddress.contains("fd00::2/128"))
    }

    // -------------------------------------------------------------
    // 11. ShadowTLS
    // -------------------------------------------------------------
    @Test
    fun testShadowTLSV3BeanCreation() {
        val bean = ShadowTLSBean().applyDefaultValues().apply {
            serverAddress = "stls.example.com"
            serverPort = 443
            password = "shadowpass"
            sni = "gateway.icloud.com"
            version = 3
            name = "SampleShadowTLS"
        }
        assertEquals("stls.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("shadowpass", bean.password)
        assertEquals("gateway.icloud.com", bean.sni)
        assertEquals(3, bean.version)
        assertEquals("SampleShadowTLS", bean.name)
    }

    // -------------------------------------------------------------
    // 12. AnyTLS
    // -------------------------------------------------------------
    @Test
    fun testAnyTLSRoundTrip() {
        val link = "anytls://anypass@anytls.example.com:443?sni=anytls.example.com#SampleAnyTLS"
        val bean = parseAnytls(link).applyDefaultValues()
        assertEquals("anytls.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("anypass", bean.password)
        assertEquals("anytls.example.com", bean.sni)
        assertEquals("SampleAnyTLS", bean.name)

        val uri = bean.toUri()
        val reparsed = parseAnytls(uri).applyDefaultValues()
        assertEquals(bean.serverAddress, reparsed.serverAddress)
        assertEquals(bean.serverPort, reparsed.serverPort)
        assertEquals(bean.password, reparsed.password)
    }

    // -------------------------------------------------------------
    // 13. Chain
    // -------------------------------------------------------------
    @Test
    fun testChainMultiHopAssembly() {
        val chain = ChainBean().apply {
            name = "MyThreeHopChain"
            proxies = mutableListOf(101L, 102L, 103L)
        }
        assertEquals("MyThreeHopChain", chain.name)
        assertEquals(3, chain.proxies.size)
        assertEquals(101L, chain.proxies[0])
        assertEquals(102L, chain.proxies[1])
        assertEquals(103L, chain.proxies[2])
    }

    // -------------------------------------------------------------
    // 14. Custom Config
    // -------------------------------------------------------------
    @Test
    fun testCustomConfigValidJson() {
        val validJson = """
            {
                "outbounds": [
                    {
                        "type": "direct",
                        "tag": "direct"
                    }
                ]
            }
        """.trimIndent()
        val configBean = ConfigBean().apply {
            name = "CustomDirect"
            config = validJson
        }
        assertEquals("CustomDirect", configBean.name)
        assertTrue(configBean.config.contains("direct"))
    }
}
