package io.nekohasekai.sagernet.fmt.hysteria

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.json.JSONObject
import java.io.File


// hysteria://host:port?auth=123456&peer=sni.domain&insecure=1|0&upmbps=100&downmbps=100&alpn=hysteria&obfs=xplus&obfsParam=123456#remarks
fun parseHysteria1(url: String): HysteriaBean =
    io.nekohasekai.sagernet.fmt.RustProxyParser.parse(url) as HysteriaBean

fun parseHysteria2(url: String): HysteriaBean =
    io.nekohasekai.sagernet.fmt.RustProxyParser.parse(url) as HysteriaBean

fun HysteriaBean.toUri(): String {
    var un = ""
    var pw = ""
    if (protocolVersion == 2) {
        if (authPayload.contains(":")) {
            un = authPayload.substringBefore(":")
            pw = authPayload.substringAfter(":")
        } else {
            un = authPayload
        }
    }
    //
    val builder = linkBuilder()
        .host(serverAddress)
        .port(getFirstPort(serverPorts))
        .username(un)
        .password(pw)
    if (isMultiPort(displayAddress())) {
        builder.addQueryParameter("mport", serverPorts)
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (protocolVersion == 1) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("peer", sni)
        }
        if (authPayload.isNotBlank()) {
            builder.addQueryParameter("auth", authPayload)
        }
        builder.addQueryParameter("upmbps", "$uploadMbps")
        builder.addQueryParameter("downmbps", "$downloadMbps")
        if (alpn.isNotBlank()) {
            builder.addQueryParameter("alpn", alpn)
        }
        if (obfuscation.isNotBlank()) {
            builder.addQueryParameter("obfs", "xplus")
            builder.addQueryParameter("obfsParam", obfuscation)
        }
    } else {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (obfuscation.isNotBlank()) {
            if (obfsType?.equals("gecko", ignoreCase = true) == true) {
                builder.addQueryParameter("obfs", "gecko")
            } else {
                builder.addQueryParameter("obfs", "salamander")
            }
            builder.addQueryParameter("obfs-password", obfuscation)
        }
    }
    return builder.toLink(if (protocolVersion == 2) "hy2" else "hysteria")
}

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

fun getFirstPort(portStr: String): Int {
    return portStr.substringBefore(":").substringBefore(",").toIntOrNull() ?: 443
}

fun buildSingBoxOutboundHysteriaBean(bean: HysteriaBean, globalAllowInsecure: Boolean = DataStore.globalAllowInsecure): SingBoxOptions.SingBoxOption {
    return when (bean.protocolVersion) {
        1 -> {
            if (bean.protocol != null && bean.protocol != HysteriaBean.PROTOCOL_UDP) {
                error("Unsupported Hysteria 1 protocol (${bean.protocol}): external plugin mode has been removed")
            }
            SingBoxOptions.Outbound_HysteriaOptions().apply {
                type = "hysteria"
                server = bean.serverAddress
                val port = bean.serverPorts.toIntOrNull()
                if (port != null) {
                    server_port = port
                } else {
                    server_ports = hopPortsToSingboxList(bean.serverPorts)
                }
                hop_interval = "${bean.hopInterval}s"
                up_mbps = bean.uploadMbps
                down_mbps = bean.downloadMbps
                obfs = bean.obfuscation
                when (bean.authPayloadType) {
                    HysteriaBean.TYPE_BASE64 -> auth = bean.authPayload
                    HysteriaBean.TYPE_STRING -> auth_str = bean.authPayload
                }
                tls = SingBoxOptions.OutboundTLSOptions().apply {
                    if (bean.sni.isNotBlank()) {
                        server_name = bean.sni
                    }
                    if (bean.alpn.isNotBlank()) {
                        alpn = bean.alpn.listByLineOrComma()
                    }
                    if (bean.caText.isNotBlank()) {
                        certificate = bean.caText
                    }
                    insecure = bean.allowInsecure || globalAllowInsecure
                    enabled = true
                }
            }
        }

        2 -> SingBoxOptions.Outbound_Hysteria2Options().apply {
            type = "hysteria2"
            server = bean.serverAddress
            val port = bean.serverPorts.toIntOrNull()
            if (port != null) {
                server_port = port
            } else {
                server_ports = hopPortsToSingboxList(bean.serverPorts)
            }
            hop_interval = "${bean.hopInterval}s"
            if (bean.hopIntervalMax != null && bean.hopIntervalMax > 0) {
                hop_interval_max = "${bean.hopIntervalMax}s"
            }
            up_mbps = bean.uploadMbps
            down_mbps = bean.downloadMbps
            if (!bean.bbrProfile.isNullOrBlank() && !bean.bbrProfile.equals("default", ignoreCase = true)) {
                bbr_profile = bean.bbrProfile.lowercase()
            }
            if (bean.disableChromeParrot == true) {
                disable_chrome_parrot = true
            }
            if (bean.obfuscation.isNotBlank()) {
                obfs = SingBoxOptions.Hysteria2Obfs().apply {
                    val oType = if (bean.obfsType?.equals("gecko", ignoreCase = true) == true) "gecko" else "salamander"
                    type = oType
                    password = bean.obfuscation
                    if (oType == "gecko") {
                        if (bean.obfsMinPacketSize != null && bean.obfsMinPacketSize != 512) {
                            min_packet_size = bean.obfsMinPacketSize
                        }
                        if (bean.obfsMaxPacketSize != null && bean.obfsMaxPacketSize != 1200) {
                            max_packet_size = bean.obfsMaxPacketSize
                        }
                    }
                }
            }
            password = bean.authPayload
            tls = SingBoxOptions.OutboundTLSOptions().apply {
                if (!bean.sni.isNullOrBlank()) {
                    server_name = bean.sni
                }
                alpn = listOf("h3")
                if (!bean.caText.isNullOrBlank()) {
                    certificate = bean.caText
                }
                insecure = bean.allowInsecure || globalAllowInsecure
                enabled = true
            }
        }

        else -> error("error_version $bean.protocolVersion")
    }
}

fun hopPortsToSingboxList(s: String): List<String> {
    return s.split(",").mapNotNull {
        val pRange = it.replace("-", ":")
        if (pRange.split(":").size == 2) {
            pRange
        } else {
            null
        }
    }
}
