package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.RustOutboundConfig
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.wireguard.*
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.proxy.anytls.*
import moe.matsuri.nb4a.proxy.shadowtls.*
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.*
import org.junit.Test

abstract class RemainingOutboundConfigCases : OutboundConfigCases() {
    @Test fun standardProtocolTlsTransportMatrixMatchesLegacy() {
        val paths = listOf("", " ", "/ws?ed=2048", "/ws?ed=invalid", "/ws?ed=+٢٣", "/ws?ed=2147483648", "/ws?ed=-1", "/ws?ed=１２")
        for (kind in listOf("http", "trojan", "vmess", "vless", "shadowtls"))
        for (transport in listOf("tcp", "ws", "http", "quic", "grpc", "httpupgrade", "unknown"))
        for (n in 0 until 24) {
            val bean: StandardV2RayBean = when (kind) {
                "http" -> HttpBean()
                "trojan" -> TrojanBean()
                "shadowtls" -> ShadowTLSBean()
                else -> VMessBean()
            }.applyDefaultValues().apply {
                serverAddress = "example.com"; serverPort = 443
                type = transport; host = if (n % 2 == 0) " a.example,b.example," else ""
                path = paths[n % paths.size]; security = listOf("tls", "none", "reality")[n % 3]
                uuid = "00000000-0000-4000-8000-000000000001"
                encryption = listOf("", "auto", "xtls-rprx-vision", "chacha20-poly1305")[n % 4]
                sni = if (n % 2 == 0) "sni.example" else "\u00a0"
                alpn = if (n % 2 == 0) "h2, h3\n" else ""
                allowInsecure = n % 3 == 0
                certificates = if (n % 4 == 0) "synthetic\ncertificate" else ""
                realityPubKey = if (n % 4 == 1) "public-key" else ""
                realityShortId = "1234"; utlsFingerprint = if (n % 5 == 0) "firefox" else ""
                enableECH = n % 2 == 0; echConfig = if (n % 3 == 0) "a\r\nb\rc\n" else ""
                wsMaxEarlyData = if (n % 4 == 0) 1024 else 0
                earlyDataHeaderName = if (n % 5 == 0) "X-Early" else ""
                packetEncoding = n % 4
                when (this) {
                    is VMessBean -> alterId = if (kind == "vless") -1 else n
                    is HttpBean -> { username = "user"; password = "pass" }
                    is TrojanBean -> password = "pass"
                    is ShadowTLSBean -> { version = 3; password = "pass" }
                }
            }
            val global = n % 2 == 0
            val expected = if (bean is ShadowTLSBean) buildSingBoxOutboundShadowTLSBean(bean, global)
                else buildSingBoxOutboundStandardV2RayBean(bean, global)
            assertEquals("$kind/$transport/$n", expected.asMap(), RustOutboundConfig.capture(bean, global)!!.generate().asMap())
        }
    }

    @Test fun hysteriaHopObfsBbrAndAuthenticationMatchLegacy() {
        val ports = listOf("443", "+٤٤٣", "443-8443", "443:8443,80,100-200", " ", "2147483648", "1:2:3", "４４３")
        for (version in listOf(1, 2)) for (n in 0 until 64) {
            val bean = HysteriaBean().applyDefaultValues().apply {
                protocolVersion = version; serverAddress = "example.org"; serverPorts = ports[n % ports.size]
                authPayload = "test-auth"; authPayloadType = n % 4
                obfuscation = if (n % 3 == 0) "" else "secret"; obfsType = if (n % 2 == 0) "GECKO" else "salamander"
                obfsMinPacketSize = listOf(null, 512, 100, 1200)[n % 4]
                obfsMaxPacketSize = listOf(1400, null, 1200, 600)[n % 4]
                bbrProfile = listOf(null, "default", "DEFAULT", "AGGRESSIVE")[n % 4]
                disableChromeParrot = n % 2 == 0; hopInterval = 10; hopIntervalMax = n % 3
                sni = if (n % 2 == 0) "sni.example" else ""; alpn = "a, b\nc"
                caText = if (n % 3 == 0) "test-cert" else ""; allowInsecure = n % 3 == 0
                uploadMbps = n; downloadMbps = n * 2
            }
            assertEquals("hy$version/$n", buildSingBoxOutboundHysteriaBean(bean, n % 2 == 0).asMap(),
                RustOutboundConfig.capture(bean, n % 2 == 0)!!.generate().asMap())
        }
        for (version in listOf(0, 3)) {
            val bean = HysteriaBean().applyDefaultValues().apply { protocolVersion = version }
            assertTrue(runCatching { RustOutboundConfig.capture(bean, false)!!.generate() }.isFailure)
        }
        val removed = HysteriaBean().applyDefaultValues().apply { protocolVersion = 1; protocol = 1 }
        assertTrue(runCatching { RustOutboundConfig.capture(removed, false)!!.generate() }.isFailure)
    }

    @Test fun wireguardIsGeneratedAsEndpointWithExactReservedSemantics() {
        for (reserved in listOf("", "1,2,3", "[1, 2, 255]", "1,-1,256", "١,２,3", "1,2", "a,b,c", "1,2,3,4"))
        for (mtu in listOf(-1, 0, 1280)) {
            val bean = WireGuardBean().applyDefaultValues().apply {
                serverAddress = "127.0.0.1"; serverPort = 51820; this.mtu = mtu
                localAddress = "10.0.0.2/32, fd00::2/128\n"; this.reserved = reserved
                privateKey = "private"; peerPublicKey = "public"; peerPreSharedKey = if (mtu > 0) "shared" else ""
            }
            assertEquals(buildSingBoxOutboundWireguardBean(bean).asMap(), RustOutboundConfig.capture(bean, false)!!.generate().asMap())
        }
    }

    @Test fun anytlsDoesNotInheritUnrelatedGlobalTlsPolicy() {
        for (n in 0 until 16) {
            val bean = AnyTLSBean().applyDefaultValues().apply {
                password = "secret"; sni = if (n % 2 == 0) "sni" else ""
                allowInsecure = n % 3 == 0; alpn = if (n % 2 == 0) "h2, h3" else ""
                certificates = if (n % 4 == 0) "cert" else ""
                utlsFingerprint = if (n % 4 == 1) "chrome" else ""
                echConfig = if (n % 4 == 2) "a\nb" else ""
            }
            assertEquals(buildSingBoxOutboundAnyTLSBean(bean).asMap(), RustOutboundConfig.capture(bean, true)!!.generate().asMap())
        }
    }

    @Test fun customOutboundsRetainObjectsAndLenientInput() {
        for (config in listOf("{}", "null", "{type:'direct',tag:'custom'}", """{"type":"socks","server":"127.0.0.1","server_port":1080,"x":{"a":[1,true,null]}}""")) {
            val bean = ConfigBean().applyDefaultValues().apply { this.config = config }
            assertEquals(CustomSingBoxOption(config).asMap(), RustOutboundConfig.capture(bean, false)!!.generate().asMap())
        }
    }
}
