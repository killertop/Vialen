package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.RustOutboundConfig
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import org.junit.Assert.*
import org.junit.Test

/** Both host JNI and Android run against the actual unchanged Kotlin generators. */
abstract class OutboundConfigCases {
    @Test fun socksVersionsPortsCredentialsAndUnicodeMatchLegacy() {
        for (protocol in listOf(-1, 0, 1, 2, 3)) for (port in listOf(-1, 0, 1080, 65535, 65536)) {
            val bean = SOCKSBean().applyDefaultValues().apply {
                this.protocol = protocol; serverPort = port
                serverAddress = "例子.test"; username = "u\"\n🦀"; password = "p\\\u0000"
            }
            assertEquals(buildSingBoxOutboundSocksBean(bean).asMap(),
                RustOutboundConfig.capture(bean, false)!!.generate().asMap())
        }
    }

    @Test fun shadowsocksPluginSplittingAndOmissionsMatchLegacy() {
        for (plugin in listOf("", " ", "\u001c\u00a0", "\u0085", "none", "none;x=1", "NONE;x", ";",
            "obfs-local", "obfs-local;", "obfs-local;obfs=http;obfs-host=x", "🦀;汉字\"\n")) {
            val bean = ShadowsocksBean().applyDefaultValues().apply {
                method = "2022-blake3-aes-128-gcm"; password = "x:y"; this.plugin = plugin
            }
            assertEquals("plugin case", buildSingBoxOutboundShadowsocksBean(bean).asMap(),
                RustOutboundConfig.capture(bean, true)!!.generate().asMap())
        }
    }

    @Test fun tuicTlsAndTransportMatrixMatchesLegacy() {
        for (global in listOf(false, true)) for (insecure in listOf(false, true))
        for (sni in listOf("", "sni.example", "\u001c\u00a0", "\u0085"))
        for (alpn in listOf("", "h3, h2\n h3 ", ",\n,", "\u00a0h3\u2007,\u0085"))
        for (mode in listOf("native", "quic", "other")) {
            val bean = TuicBean().applyDefaultValues().apply {
                serverAddress = "2001:db8::1"; serverPort = 443
                uuid = "00000000-0000-4000-8000-000000000001"; token = "token🦀"
                congestionController = "bbr"; udpRelayMode = mode; reduceRTT = global
                this.sni = sni; this.alpn = alpn; caText = if (insecure) "certificate\ntext" else ""
                disableSNI = insecure; allowInsecure = insecure
            }
            assertEquals(buildSingBoxOutboundTuicBean(bean, global).asMap(),
                RustOutboundConfig.capture(bean, global)!!.generate().asMap())
        }
    }

    @Test fun snapshotsAndExistingOverlayOrderArePreserved() {
        val bean = TuicBean().applyDefaultValues().apply { token = "old-token" }
        val old = buildSingBoxOutboundTuicBean(bean, true)
        val snapshot = RustOutboundConfig.capture(bean, true)!!
        bean.token = "new-token"; bean.serverPort = 10000; bean.caText = "new cert"
        val generated = snapshot.generate()
        assertEquals(old.asMap(), generated.asMap())
        assertFalse(snapshot.toString().contains("old-token"))
        fun decorate(out: SingBoxOption) {
            out._hack_config_map["tag"] = "proxy"
            out._hack_config_map["detour"] = "g-42"
            out._hack_config_map["domain_strategy"] = "prefer_ipv4"
            out._hack_config_map["udp_over_tcp"] = true
            out._hack_config_map["multiplex"] = mapOf("enabled" to true, "protocol" to "h2mux")
            out._hack_custom_config = """{"tag":"user-tag","tls":{"insecure":false,"server_name":"override"}}"""
        }
        decorate(old); decorate(generated)
        assertEquals(old.asMap(), generated.asMap())
        assertEquals("user-tag", generated.asMap()["tag"])
    }

    @Test fun unsupportedInputsUseExplicitLegacyBoundaryAndRemovedTuicStillRejects() {
        assertNull(RustOutboundConfig.capture(TrojanBean().applyDefaultValues(), false))
        assertNull(RustOutboundConfig.capture(SOCKSBean(), false))
        val malformed = SOCKSBean().applyDefaultValues().apply { password = "\uD800" }
        assertNull(RustOutboundConfig.capture(malformed, false))
        val tuic4 = TuicBean().applyDefaultValues().apply { protocolVersion = 4 }
        assertTrue(runCatching { buildSingBoxOutboundTuicBean(tuic4, false) }.isFailure)
        assertTrue(runCatching { RustOutboundConfig.capture(tuic4, false)!!.generate() }.isFailure)
    }

    @Test fun wireErrorsFailClosed() {
        for (json in listOf("{}", """{"version":2,"status":"SUCCESS","outbound":{}}""",
            """{"version":1.5,"status":"SUCCESS","outbound":{}}""",
            """{"version":1,"status":"SUCCESS","outbound":{}}""",
            """{"version":1,"status":"ERROR","error":"INVALID_INPUT"}""")) {
            assertTrue(runCatching { RustOutboundConfig.decode(json.toByteArray()) }.isFailure)
        }
        val response = RustBridge.generateOutbound("""{"version":1,"profile":{"kind":"unknown"}}""".toByteArray())
        assertTrue(runCatching { RustOutboundConfig.decode(response) }.isFailure)
    }
}
