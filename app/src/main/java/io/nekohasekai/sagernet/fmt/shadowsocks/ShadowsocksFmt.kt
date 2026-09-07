package io.nekohasekai.sagernet.fmt.shadowsocks

import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun ShadowsocksBean.fixPluginName() {
    if (plugin.startsWith("simple-obfs")) {
        plugin = plugin.replaceFirst("simple-obfs", "obfs-local")
    }
}

fun parseShadowsocks(url: String): ShadowsocksBean {
    if (!url.startsWith("ss://")) {
        error("invalid ss link $url: invalid scheme")
    }
    val res = RustBridge.parseProxy(url)
    if (res.status != "SUCCESS") {
        error("invalid ss link $url: ${res.error ?: res.status}")
    }
    return ShadowsocksBean().apply {
        serverAddress = res.server
        serverPort = res.port
        method = res.username
        password = res.password
        plugin = res.plugin
        name = res.name.ifEmpty { null }
        fixPluginName()
    }
}

fun ShadowsocksBean.toUri(): String {

    val builder = linkBuilder().username(Util.b64EncodeUrlSafe("$method:$password"))
        .host(serverAddress)
        .port(serverPort)

    if (plugin.isNotBlank()) {
        builder.addQueryParameter("plugin", plugin)
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("ss").replace("$serverPort/", "$serverPort")

}

fun JSONObject.parseShadowsocks(): ShadowsocksBean {
    return ShadowsocksBean().apply {
        serverAddress = getStr("server")
        serverPort = getIntNya("server_port")
        password = getStr("password")
        method = getStr("method")
        name = optString("remarks", "")

        val pId = getStr("plugin")
        if (!pId.isNullOrBlank()) {
            plugin = pId + ";" + optString("plugin_opts", "")
        }
    }
}

fun buildSingBoxOutboundShadowsocksBean(bean: ShadowsocksBean): SingBoxOptions.Outbound_ShadowsocksOptions {
    return SingBoxOptions.Outbound_ShadowsocksOptions().apply {
        type = "shadowsocks"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password
        method = bean.method
        if (bean.plugin.isNotBlank()) {
            plugin = bean.plugin.substringBefore(";")
            plugin_opts = bean.plugin.substringAfter(";")
            if (plugin == "none") {
                plugin = null
                plugin_opts = null
            }
        }
    }
}
