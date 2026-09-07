package io.nekohasekai.sagernet.fmt.socks

import io.nekohasekai.sagernet.ktx.decodeBase64UrlSafe
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.unUrlSafe
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import io.nekohasekai.sagernet.rust.RustBridge

fun parseSOCKS(link: String): SOCKSBean {
    if (!link.startsWith("socks://") && !link.startsWith("socks4://") && !link.startsWith("socks4a://") && !link.startsWith("socks5://")) {
        error("Not supported: $link")
    }
    val res = RustBridge.parseProxy(link)
    if (res.status != "SUCCESS") {
        error("Not supported: $link: ${res.error ?: res.status}")
    }

    return SOCKSBean().apply {
        protocol = when (res.protocol) {
            "socks4" -> SOCKSBean.PROTOCOL_SOCKS4
            "socks4a" -> SOCKSBean.PROTOCOL_SOCKS4A
            else -> SOCKSBean.PROTOCOL_SOCKS5
        }
        name = res.name.ifEmpty { null }
        serverAddress = res.server
        serverPort = res.port
        username = res.username
        password = res.password
    }
}

fun SOCKSBean.toUri(): String {

    val builder = HttpUrl.Builder().scheme("http").host(serverAddress).port(serverPort)
    if (!username.isNullOrBlank()) builder.username(username)
    if (!password.isNullOrBlank()) builder.password(password)
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("socks${protocolVersion()}")

}

fun SOCKSBean.toV2rayN(): String {

    var link = ""
    if (username.isNotBlank()) {
        link += username.urlSafe() + ":" + password.urlSafe() + "@"
    }
    link += "$serverAddress:$serverPort"
    link = "socks://" + NGUtil.encode(link)
    if (name.isNotBlank()) {
        link += "#" + name.urlSafe()
    }

    return link

}

fun buildSingBoxOutboundSocksBean(bean: SOCKSBean): SingBoxOptions.Outbound_SocksOptions {
    return SingBoxOptions.Outbound_SocksOptions().apply {
        type = "socks"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        version = bean.protocolVersionName()
    }
}
