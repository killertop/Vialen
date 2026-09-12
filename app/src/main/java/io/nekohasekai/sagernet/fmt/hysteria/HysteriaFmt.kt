package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.ktx.*
import org.json.JSONObject


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean =
    io.nekohasekai.sagernet.fmt.CoreProxyParser.parse(url) as HysteriaBean

fun parseHysteria2(url: String): HysteriaBean =
    io.nekohasekai.sagernet.fmt.CoreProxyParser.parse(url) as HysteriaBean

fun HysteriaBean.toUri(): String =
    io.nekohasekai.sagernet.core.CoreClient.exportURI(
        io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(this)
    )

fun JSONObject.parseHysteria1Json(): HysteriaBean {
    // TODO parse HY2 JSON+YAML
    return HysteriaBean().apply {
        protocolVersion = 1
        serverAddress = optString("server").substringBeforeLast(":")
        serverPorts = optString("server").substringAfterLast(":")
        uploadMbps = getIntNya("up_mbps")
        downloadMbps = getIntNya("down_mbps")
        obfuscation = getStr("obfs")
        getStr("auth")?.also {
            authPayloadType = HysteriaBean.TYPE_BASE64
            authPayload = it
        }
        getStr("auth_str")?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        getStr("protocol")?.also {
            when (it.lowercase()) {
                "faketcp", "wechat-video" -> {
                    throw IllegalArgumentException("Unsupported Hysteria 1 protocol '$it': external plugin mode has been removed")
                }
                "udp" -> {
                    protocol = HysteriaBean.PROTOCOL_UDP
                }
                else -> {
                    throw IllegalArgumentException("Unknown Hysteria 1 protocol: $it")
                }
            }
        }
        sni = getStr("server_name")
        alpn = getStr("alpn")
        allowInsecure = getBool("insecure")

        streamReceiveWindow = getIntNya("recv_window_conn")
        connectionReceiveWindow = getIntNya("recv_window")
        disableMtuDiscovery = getBool("disable_mtu_discovery")
    }
}

fun isMultiPort(hyAddr: String): Boolean {
    if (!hyAddr.contains(":")) return false
    val p = hyAddr.substringAfterLast(":")
    if (p.contains("-") || p.contains(",")) return true
    return false
}
