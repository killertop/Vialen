package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import io.nekohasekai.sagernet.fmt.RustOutboundConfig
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import libcore.Libcore
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutboundConfigNativeTest : RemainingOutboundConfigCases() {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val benchmarkForeground = BenchmarkForegroundRule()
    @Test fun generatedOutboundsInitializeInPackagedCoreWithoutNetwork() {
        val beans: List<AbstractBean> = listOf(
            SOCKSBean().applyDefaultValues(),
            HttpBean().applyDefaultValues(),
            TrojanBean().applyDefaultValues().apply { password="synthetic-password"; security="tls" },
            VMessBean().applyDefaultValues().apply { uuid="00000000-0000-4000-8000-000000000001" },
            VMessBean().applyDefaultValues().apply { uuid="00000000-0000-4000-8000-000000000001"; alterId=-1 },
            ShadowTLSBean().applyDefaultValues().apply { password="synthetic-password" },
            HysteriaBean().applyDefaultValues().apply { protocolVersion=1; serverPorts="443"; uploadMbps=100; downloadMbps=100 },
            HysteriaBean().applyDefaultValues().apply { protocolVersion=2; serverPorts="443"; authPayload="synthetic-password" },
            WireGuardBean().applyDefaultValues().apply {
                privateKey="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                peerPublicKey="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
                localAddress="10.0.0.1/32"
            },
            AnyTLSBean().applyDefaultValues().apply { password="synthetic-password" },
            ConfigBean().applyDefaultValues().apply { type=1; config="""{"type":"socks","server":"127.0.0.1","server_port":1080}""" },
            ShadowsocksBean().applyDefaultValues().apply {
                method = "chacha20-ietf-poly1305"; password = "synthetic-test-password"
            },
            TuicBean().applyDefaultValues().apply {
                uuid = "00000000-0000-4000-8000-000000000001"; token = "synthetic-test-token"
            }
        )
        for (bean in beans) {
            bean.serverAddress = "127.0.0.1"; bean.serverPort = 1080
            val outbound = RustOutboundConfig.capture(bean, false)!!.generate()
            outbound._hack_config_map["tag"] = "proxy"
            val config = gson.toJson(mapOf((if(bean is WireGuardBean) "endpoints" else "outbounds") to listOf(outbound.asMap()),
                "route" to mapOf("final" to "proxy")))
            // Construct/close only: no start(), sockets, TUN or upstream requests.
            Libcore.newSingBoxInstance(config, null).close()
        }
    }
}
