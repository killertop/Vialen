package io.nekohasekai.sagernet.oracle

// Frozen Kotlin parser oracle from f784dc7. Compiled only into tests.
import android.text.TextUtils
import com.google.gson.Gson
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import io.nekohasekai.sagernet.fmt.v2ray.*

fun parseV2Ray(link: String): StandardV2RayBean {
    // Try parse stupid formats first

    if (!link.contains("?")) {
        try {
            return parseV2RayN(link)
        } catch (e: Exception) {
            Logs.i("try v2rayN: " + e.readableMessage)
        }
    }

    try {
        return tryResolveVmess4Kitsunebi(link)
    } catch (e: Exception) {
        Logs.i("try Kitsunebi: " + e.readableMessage)
    }

    // "std" format

    val bean = VMessBean().apply { if (link.startsWith("vless://")) alterId = -1 }
    val url = link.replace("vmess://", "https://").replace("vless://", "https://").toHttpUrl()

    if (url.password.isNotBlank()) {
        // https://github.com/v2fly/v2fly-github-io/issues/26 (rarely use)
        bean.serverAddress = url.host
        bean.serverPort = url.port
        bean.name = url.fragment

        var protocol = url.username
        bean.type = protocol
        bean.alterId = url.password.substringAfterLast('-').toInt()
        bean.uuid = url.password.substringBeforeLast('-')

        if (protocol.endsWith("+tls")) {
            bean.security = "tls"
            protocol = protocol.substring(0, protocol.length - 4)

            url.queryParameter("tlsServerName")?.let {
                if (it.isNotBlank()) {
                    bean.sni = it
                }
            }
        }

        when (protocol) {
//            "tcp" -> {
//                url.queryParameter("type")?.let { type ->
//                    if (type == "http") {
//                        bean.headerType = "http"
//                        url.queryParameter("host")?.let {
//                            bean.host = it
//                        }
//                    }
//                }
//            }
            "http" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it.split("|").joinToString(",")
                }
            }

            "ws" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }

            "grpc" -> {
                url.queryParameter("serviceName")?.let {
                    bean.path = it
                }
            }

            "httpupgrade" -> {
                url.queryParameter("path")?.let {
                    bean.path = it
                }
                url.queryParameter("host")?.let {
                    bean.host = it
                }
            }
        }
    } else {
        // also vless format
        bean.parseDuckSoft(url)
    }

    return bean
}

// https://github.com/XTLS/Xray-core/issues/91
fun StandardV2RayBean.parseDuckSoft(url: HttpUrl) {
    serverAddress = url.host
    serverPort = url.port
    name = url.fragment

    if (this is TrojanBean) {
        password = url.username
    } else {
        uuid = url.username
    }

    // not ducksoft fmt path
    if (url.pathSegments.size > 1 || url.pathSegments[0].isNotBlank()) {
        path = url.pathSegments.joinToString("/")
    }

    type = url.queryParameter("type") ?: "tcp"
    if (type == "h2" || url.queryParameter("headerType") == "http") type = "http"

    security = url.queryParameter("security")
    if (security.isNullOrBlank()) {
        security = if (this is TrojanBean) "tls" else "none"
    }

    when (security) {
        "tls", "reality" -> {
            security = "tls"
            url.queryParameter("allowInsecure")?.let {
                allowInsecure = it == "1" || it == "true"
            }
            url.queryParameter("sni")?.let {
                sni = it
            }
            url.queryParameter("host")?.let {
                if (sni.isNullOrBlank()) sni = it
            }
            url.queryParameter("alpn")?.let {
                alpn = it
            }
            url.queryParameter("cert")?.let {
                certificates = it
            }
            url.queryParameter("pbk")?.let {
                realityPubKey = it
            }
            url.queryParameter("sid")?.let {
                realityShortId = it
            }
        }
    }

    when (type) {
        "http" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
        }

        "ws" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
            url.queryParameter("ed")?.let { ed ->
                wsMaxEarlyData = ed.toInt()

                url.queryParameter("eh")?.let {
                    earlyDataHeaderName = it
                }
            }
        }

        "grpc" -> {
            url.queryParameter("serviceName")?.let {
                path = it
            }
        }

        "httpupgrade" -> {
            url.queryParameter("host")?.let {
                host = it
            }
            url.queryParameter("path")?.let {
                path = it
            }
        }
    }

    // maybe from matsuri vmess exoprt
    if (this is VMessBean && !isVLESS) {
        url.queryParameter("encryption")?.let {
            encryption = it
        }
    }

    url.queryParameter("packetEncoding")?.let {
        when (it) {
            "packet" -> packetEncoding = 1
            "xudp" -> packetEncoding = 2
        }
    }

    url.queryParameter("flow")?.let {
        if (isVLESS) {
            encryption = it.removeSuffix("-udp443")
        }
    }

    url.queryParameter("fp")?.let {
        utlsFingerprint = it
    }
}

// 不确定是谁的格式
private fun tryResolveVmess4Kitsunebi(server: String): VMessBean {
    // vmess://YXV0bzo1YWY1ZDBlYy02ZWEwLTNjNDMtOTNkYi1jYTMwMDg1MDNiZGJAMTgzLjIzMi41Ni4xNjE6MTIwMg
    // ?remarks=*%F0%9F%87%AF%F0%9F%87%B5JP%20-355%20TG@moon365free&obfsParam=%7B%22Host%22:%22183.232.56.161%22%7D&path=/v2ray&obfs=websocket&alterId=0

    var result = server.replace("vmess://", "")
    val indexSplit = result.indexOf("?")
    if (indexSplit > 0) {
        result = result.substring(0, indexSplit)
    }
    result = NGUtil.decode(result)

    val arr1 = result.split('@')
    if (arr1.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }
    val arr21 = arr1[0].split(':')
    val arr22 = arr1[1].split(':')
    if (arr21.count() != 2) {
        throw IllegalStateException("invalid kitsunebi format")
    }

    return VMessBean().apply {
        serverAddress = arr22[0]
        serverPort = NGUtil.parseInt(arr22[1])
        uuid = arr21[1]
        encryption = arr21[0]
        if (indexSplit < 0) return@apply

        val url = ("https://localhost/path?" + server.substringAfter("?")).toHttpUrl()
        url.queryParameter("remarks")?.apply { name = this }
        url.queryParameter("alterId")?.apply { alterId = this.toInt() }
        url.queryParameter("path")?.apply { path = this }
        url.queryParameter("tls")?.apply { security = "tls" }
        url.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        url.queryParameter("obfs")?.apply {
            type = this.replace("websocket", "ws").replace("none", "tcp")
            if (type == "ws") {
                url.queryParameter("obfsParam")?.apply {
                    if (this.startsWith("{")) {
                        host = JSONObject(this).getStr("Host")
                    } else if (security == "tls") {
                        sni = this
                    }
                }
            }
        }
    }
}

// SagerNet's
// Do not support some format and then throw exception
fun parseV2RayN(link: String): VMessBean {
    val result = link.substringAfter("vmess://").decodeBase64UrlSafe()
    if (result.contains("= vmess")) {
        return parseCsvVMess(result)
    }
    val bean = VMessBean()
    val vmessQRCode = Gson().fromJson(result, VmessQRCode::class.java)

    // Although VmessQRCode fields are non null, looks like Gson may still create null fields
    if (TextUtils.isEmpty(vmessQRCode.add)
        || TextUtils.isEmpty(vmessQRCode.port)
        || TextUtils.isEmpty(vmessQRCode.id)
        || TextUtils.isEmpty(vmessQRCode.net)
    ) {
        throw Exception("invalid VmessQRCode")
    }

    bean.name = vmessQRCode.ps
    bean.serverAddress = vmessQRCode.add
    bean.serverPort = vmessQRCode.port.toIntOrNull()
    bean.encryption = vmessQRCode.scy
    bean.uuid = vmessQRCode.id
    bean.alterId = vmessQRCode.aid.toIntOrNull()
    bean.type = vmessQRCode.net
    bean.host = vmessQRCode.host
    bean.path = vmessQRCode.path
    val headerType = vmessQRCode.type

    when (bean.type) {
        "tcp" -> {
            if (headerType == "http") {
                bean.type = "http"
            }
        }
    }
    when (vmessQRCode.tls) {
        "tls", "reality" -> {
            bean.security = "tls"
            bean.sni = vmessQRCode.sni
            if (bean.sni.isNullOrBlank()) bean.sni = bean.host
            bean.alpn = vmessQRCode.alpn
            bean.utlsFingerprint = vmessQRCode.fp
        }
    }

    return bean
}

private fun parseCsvVMess(csv: String): VMessBean {

    val args = csv.split(",")

    val bean = VMessBean()

    bean.serverAddress = args[1]
    bean.serverPort = args[2].toInt()
    bean.encryption = args[3]
    bean.uuid = args[4].replace("\"", "")

    args.subList(5, args.size).forEach {

        when {
            it == "over-tls=true" -> bean.security = "tls"
            it.startsWith("tls-host=") -> bean.host = it.substringAfter("=")
            it.startsWith("obfs=") -> bean.type = it.substringAfter("=")
            it.startsWith("obfs-path=") || it.contains("Host:") -> {
                runCatching {
                    bean.path = it.substringAfter("obfs-path=\"").substringBefore("\"obfs")
                }
                runCatching {
                    bean.host = it.substringAfter("Host:").substringBefore("[")
                }

            }

        }

    }

    return bean

}
