package io.nekohasekai.sagernet.fmt.v2ray

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.listByLineOrComma
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl

data class VmessQRCode(
    var v: String = "",
    var ps: String = "",
    var add: String = "",
    var port: String = "",
    var id: String = "",
    var aid: String = "0",
    var scy: String = "",
    var net: String = "",
    var type: String = "",
    var host: String = "",
    var path: String = "",
    var tls: String = "",
    var sni: String = "",
    var alpn: String = "",
    var fp: String = "",
)

fun StandardV2RayBean.isTLS(): Boolean {
    return security == "tls"
}

fun StandardV2RayBean.setTLS(boolean: Boolean) {
    security = if (boolean) "tls" else ""
}

fun parseV2Ray(link: String): StandardV2RayBean =
    io.nekohasekai.sagernet.fmt.RustProxyParser.parse(link) as VMessBean

fun VMessBean.toV2rayN(): String {
    val bean = this
    return "vmess://" + VmessQRCode().apply {
        v = "2"
        ps = bean.name
        add = bean.serverAddress
        port = bean.serverPort.toString()
        id = bean.uuid
        aid = bean.alterId.toString()
        net = bean.type
        host = bean.host
        path = bean.path

        when (net) {
            "http" -> {
                if (!isTLS()) {
                    type = "http"
                    net = "tcp"
                }
            }
        }

        if (isTLS()) {
            tls = "tls"
            if (bean.realityPubKey.isNotBlank()) {
                tls = "reality"
            }
        }

        scy = bean.encryption
        sni = bean.sni
        alpn = bean.alpn.replace("\n", ",")
        fp = bean.utlsFingerprint
    }.let {
        NGUtil.encode(Gson().toJson(it))
    }
}

fun StandardV2RayBean.toUriVMessVLESSTrojan(isTrojan: Boolean): String {
    // VMess
    if (this is VMessBean && !isVLESS) {
        return toV2rayN()
    }

    // VLESS & Trojan (ducksoft fmt)
    val builder = linkBuilder()
        .username(if (this is TrojanBean) password else uuid)
        .host(serverAddress)
        .port(serverPort)
        .addQueryParameter("type", type)

    if (isVLESS) {
        builder.addQueryParameter("encryption", "none")
        if (encryption != "auto") builder.addQueryParameter("flow", encryption)
    }

    when (type) {
        "tcp" -> {}
        "ws", "http", "httpupgrade" -> {
            if (host.isNotBlank()) {
                builder.addQueryParameter("host", host)
            }
            if (path.isNotBlank()) {
                builder.addQueryParameter("path", path)
            }
            if (type == "ws") {
                if (wsMaxEarlyData > 0) {
                    builder.addQueryParameter("ed", "$wsMaxEarlyData")
                    if (earlyDataHeaderName.isNotBlank()) {
                        builder.addQueryParameter("eh", earlyDataHeaderName)
                    }
                }
            } else if (type == "http" && !isTLS()) {
                builder.setQueryParameter("type", "tcp")
                builder.addQueryParameter("headerType", "http")
            }
        }

        "grpc" -> {
            if (path.isNotBlank()) {
                builder.setQueryParameter("serviceName", path)
            }
        }
    }

    if (security.isNotBlank() && security != "none") {
        builder.addQueryParameter("security", security)
        when (security) {
            "tls" -> {
                if (sni.isNotBlank()) {
                    builder.addQueryParameter("sni", sni)
                }
                if (alpn.isNotBlank()) {
                    builder.addQueryParameter("alpn", alpn.replace("\n", ","))
                }
                if (certificates.isNotBlank()) {
                    builder.addQueryParameter("cert", certificates)
                }
                if (allowInsecure) {
                    builder.addQueryParameter("allowInsecure", "1")
                }
                if (utlsFingerprint.isNotBlank()) {
                    builder.addQueryParameter("fp", utlsFingerprint)
                }
                if (realityPubKey.isNotBlank()) {
                    builder.setQueryParameter("security", "reality")
                    builder.addQueryParameter("pbk", realityPubKey)
                    builder.addQueryParameter("sid", realityShortId)
                }
            }
        }
    }

    when (packetEncoding) {
        1 -> {
            builder.addQueryParameter("packetEncoding", "packetaddr")
        }

        2 -> {
            builder.addQueryParameter("packetEncoding", "xudp")
        }
    }

    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink(if (isTrojan) "trojan" else "vless")
}

fun buildSingBoxOutboundStreamSettings(bean: StandardV2RayBean): V2RayTransportOptions? {
    when (bean.type) {
        "tcp" -> {
            return null
        }

        "ws" -> {
            return V2RayTransportOptions_WebsocketOptions().apply {
                type = "ws"
                headers = mutableMapOf()

                if (bean.host.isNotBlank()) {
                    headers["Host"] = bean.host
                }

                if (bean.path.contains("?ed=")) {
                    path = bean.path.substringBefore("?ed=")
                    max_early_data = bean.path.substringAfter("?ed=").toIntOrNull() ?: 2048
                    early_data_header_name = "Sec-WebSocket-Protocol"
                } else {
                    path = bean.path.takeIf { it.isNotBlank() } ?: "/"
                }

                if (bean.wsMaxEarlyData > 0) {
                    max_early_data = bean.wsMaxEarlyData
                }

                if (bean.earlyDataHeaderName.isNotBlank()) {
                    early_data_header_name = bean.earlyDataHeaderName
                }
            }
        }

        "http" -> {
            return V2RayTransportOptions_HTTPOptions().apply {
                type = "http"
                if (!bean.isTLS()) method = "GET" // v2ray tcp header
                if (bean.host.isNotBlank()) {
                    host = bean.host.split(",")
                }
                path = bean.path.takeIf { it.isNotBlank() } ?: "/"
            }
        }

        "quic" -> {
            return V2RayTransportOptions().apply {
                type = "quic"
            }
        }

        "grpc" -> {
            return V2RayTransportOptions_GRPCOptions().apply {
                type = "grpc"
                service_name = bean.path
            }
        }

        "httpupgrade" -> {
            return V2RayTransportOptions_HTTPUpgradeOptions().apply {
                type = "httpupgrade"
                host = bean.host
                path = bean.path
            }
        }
    }

    return null
}

fun buildSingBoxOutboundTLS(bean: StandardV2RayBean): OutboundTLSOptions? {
    if (bean.security != "tls") return null
    return OutboundTLSOptions().apply {
        enabled = true
        insecure = bean.allowInsecure || DataStore.globalAllowInsecure
        if (bean.sni.isNotBlank()) server_name = bean.sni
        if (bean.alpn.isNotBlank()) alpn = bean.alpn.listByLineOrComma()
        if (bean.certificates.isNotBlank()) certificate = bean.certificates
        var fp = bean.utlsFingerprint
        if (bean.realityPubKey.isNotBlank()) {
            reality = OutboundRealityOptions().apply {
                enabled = true
                public_key = bean.realityPubKey
                short_id = bean.realityShortId
            }
            if (fp.isNullOrBlank()) fp = "chrome"
        }
        if (fp.isNotBlank()) {
            utls = OutboundUTLSOptions().apply {
                enabled = true
                fingerprint = fp
            }
        }
        if (bean.enableECH) {
            ech = OutboundECHOptions().apply {
                enabled = true
                if (bean.echConfig.isNotBlank()) {
                    config = bean.echConfig.lines()
                }
            }
        }
    }
}

fun buildSingBoxOutboundStandardV2RayBean(bean: StandardV2RayBean): Outbound {
    when (bean) {
        is HttpBean -> {
            return Outbound_HTTPOptions().apply {
                type = "http"
                server = bean.serverAddress
                server_port = bean.serverPort
                username = bean.username
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
            }
        }

        is VMessBean -> {
            if (bean.isVLESS) return Outbound_VLESSOptions().apply {
                type = "vless"
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                if (bean.encryption.isNotBlank() && bean.encryption != "auto") {
                    flow = bean.encryption
                }
                when (bean.packetEncoding) {
                    0 -> packet_encoding = ""
                    1 -> packet_encoding = "packetaddr"
                    2 -> packet_encoding = "xudp"
                }
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
            return Outbound_VMessOptions().apply {
                type = "vmess"
                server = bean.serverAddress
                server_port = bean.serverPort
                uuid = bean.uuid
                alter_id = bean.alterId
                security = bean.encryption.takeIf { it.isNotBlank() } ?: "auto"
                when (bean.packetEncoding) {
                    0 -> packet_encoding = ""
                    1 -> packet_encoding = "packetaddr"
                    2 -> packet_encoding = "xudp"
                }
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        is TrojanBean -> {
            return Outbound_TrojanOptions().apply {
                type = "trojan"
                server = bean.serverAddress
                server_port = bean.serverPort
                password = bean.password
                tls = buildSingBoxOutboundTLS(bean)
                transport = buildSingBoxOutboundStreamSettings(bean)
            }
        }

        else -> throw IllegalStateException("can't reach")
    }
}
