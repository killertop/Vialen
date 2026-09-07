package moe.matsuri.nb4a.proxy.shadowtls

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundTLS
import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundShadowTLSBean(bean: ShadowTLSBean, globalAllowInsecure: Boolean = DataStore.globalAllowInsecure): SingBoxOptions.Outbound_ShadowTLSOptions {
    return SingBoxOptions.Outbound_ShadowTLSOptions().apply {
        type = "shadowtls"
        server = bean.serverAddress
        server_port = bean.serverPort
        version = bean.version
        password = bean.password
        tls = buildSingBoxOutboundTLS(bean, globalAllowInsecure)
    }
}
