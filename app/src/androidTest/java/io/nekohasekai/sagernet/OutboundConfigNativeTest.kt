package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.AbstractBean
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
class OutboundConfigNativeTest : OutboundConfigCases() {
    @Test fun generatedOutboundsInitializeInPackagedCoreWithoutNetwork() {
        val beans: List<AbstractBean> = listOf(
            SOCKSBean().applyDefaultValues(),
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
            val config = gson.toJson(mapOf("outbounds" to listOf(outbound.asMap()),
                "route" to mapOf("final" to "proxy")))
            // Construct/close only: no start(), sockets, TUN or upstream requests.
            Libcore.newSingBoxInstance(config, null).close()
        }
    }
}
