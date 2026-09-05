package io.nekohasekai.sagernet.oracle

// Frozen Kotlin parser oracle from f784dc7. Compiled only into tests.
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import io.nekohasekai.sagernet.fmt.hysteria.*

fun parseHysteria1(url: String): HysteriaBean {
    val link = url.replace("hysteria://", "https://").toHttpUrlOrNull() ?: error(
        "invalid hysteria link $url"
    )
    return HysteriaBean().apply {
        initializeDefaultValues()
        protocolVersion = 1
        serverAddress = link.host
        serverPort = link.port
        serverPorts = link.port.toString()
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("peer")?.also {
            sni = it
        }
        link.queryParameter("auth")?.takeIf { it.isNotBlank() }?.also {
            authPayloadType = HysteriaBean.TYPE_STRING
            authPayload = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("upmbps")?.also {
            uploadMbps = it.toIntOrNull() ?: uploadMbps
        }
        link.queryParameter("downmbps")?.also {
            downloadMbps = it.toIntOrNull() ?: downloadMbps
        }
        link.queryParameter("alpn")?.also {
            alpn = it
        }
        link.queryParameter("obfsParam")?.also {
            obfuscation = it
        }
        link.queryParameter("protocol")?.also {
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
    }
}

// hysteria2://[auth@]hostname[:port]/?[key=value]&[key=value]...
fun parseHysteria2(url: String): HysteriaBean {
    val link = url
        .replace("hysteria2://", "https://")
        .replace("hy2://", "https://")
        .toHttpUrlOrNull() ?: error("invalid hysteria link $url")
    return HysteriaBean().apply {
        initializeDefaultValues()
        protocolVersion = 2
        serverAddress = link.host
        serverPort = link.port
        serverPorts = link.port.toString()
        authPayload = if (link.password.isNotBlank()) {
            link.username + ":" + link.password
        } else {
            link.username
        }
        name = link.fragment

        link.queryParameter("mport")?.also {
            serverPorts = it
        }
        link.queryParameter("sni")?.also {
            sni = it
        }
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
//        link.queryParameter("upmbps")?.also {
//            uploadMbps = it.toIntOrNull() ?: uploadMbps
//        }
//        link.queryParameter("downmbps")?.also {
//            downloadMbps = it.toIntOrNull() ?: downloadMbps
//        }
        link.queryParameter("obfs")?.also {
            if (it.equals("gecko", ignoreCase = true)) {
                obfsType = "gecko"
            } else if (it.equals("salamander", ignoreCase = true)) {
                obfsType = "salamander"
            }
        }
        link.queryParameter("obfs-password")?.also {
            obfuscation = it
        }
//        link.queryParameter("pinSHA256")?.also {
//            // TODO your box do not support it
//        }
    }
}
