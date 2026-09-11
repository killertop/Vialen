package moe.matsuri.nb4a

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SingBoxOptionsUtil {

    fun domainStrategy(tag: String): String {
        fun auto2(key: String, newS: String): String {
            return (DataStore.configurationStore.getString(key) ?: "").replace("auto", newS)
        }
        return when (tag) {
            "dns-remote" -> {
                auto2("domain_strategy_for_remote", "")
            }

            "dns-direct" -> {
                auto2("domain_strategy_for_direct", "")
            }

            // server
            else -> {
                auto2("domain_strategy_for_server", "prefer_ipv4")
            }
        }
    }

    fun parseTypedDnsServer(
        serverStr: String,
        tag: String,
        domainResolver: String? = null,
        detour: String? = null
    ): DNSServerOptions {
        val s = serverStr.trim()
        return when {
            s.equals("local", ignoreCase = true) -> {
                DNSServerOptions().apply {
                    this.type = "local"
                    this.tag = tag
                    this.detour = detour
                }
            }
            s.equals("fakeip", ignoreCase = true) -> {
                DNSServerOptions().apply {
                    this.type = "fakeip"
                    this.tag = tag
                    this.inet4_range = "198.18.0.0/15"
                    this.inet6_range = "fc00::/18"
                }
            }
            s.startsWith("https://", ignoreCase = true) -> {
                val url = s.toHttpUrlOrNull()
                if (url != null) {
                    DNSServerOptions().apply {
                        this.type = "https"
                        this.tag = tag
                        this.server = url.host
                        if (url.port != 443) this.server_port = url.port
                        if (url.encodedPath != "/dns-query") this.path = url.encodedPath
                        this.domain_resolver = domainResolver
                        this.detour = detour
                    }
                } else {
                    DNSServerOptions().apply {
                        this.type = "https"
                        this.tag = tag
                        this.server = s.removePrefix("https://").substringBefore("/")
                        this.domain_resolver = domainResolver
                        this.detour = detour
                    }
                }
            }
            s.startsWith("tls://", ignoreCase = true) -> {
                val raw = s.removePrefix("tls://")
                val host = if (raw.startsWith("[")) raw.substringAfter("[").substringBefore("]") else raw.substringBefore(":")
                val port = if (raw.contains(":") && !raw.endsWith("]")) raw.substringAfterLast(":").toIntOrNull() else null
                DNSServerOptions().apply {
                    this.type = "tls"
                    this.tag = tag
                    this.server = host
                    if (port != null && port != 853) this.server_port = port
                    this.domain_resolver = domainResolver
                    this.detour = detour
                }
            }
            s.startsWith("tcp://", ignoreCase = true) -> {
                val raw = s.removePrefix("tcp://")
                val host = if (raw.startsWith("[")) raw.substringAfter("[").substringBefore("]") else raw.substringBefore(":")
                val port = if (raw.contains(":") && !raw.endsWith("]")) raw.substringAfterLast(":").toIntOrNull() else null
                DNSServerOptions().apply {
                    this.type = "tcp"
                    this.tag = tag
                    this.server = host
                    if (port != null && port != 53) this.server_port = port
                    this.domain_resolver = domainResolver
                    this.detour = detour
                }
            }
            s.startsWith("quic://", ignoreCase = true) -> {
                val raw = s.removePrefix("quic://")
                val host = if (raw.startsWith("[")) raw.substringAfter("[").substringBefore("]") else raw.substringBefore(":")
                val port = if (raw.contains(":") && !raw.endsWith("]")) raw.substringAfterLast(":").toIntOrNull() else null
                DNSServerOptions().apply {
                    this.type = "quic"
                    this.tag = tag
                    this.server = host
                    if (port != null && port != 853) this.server_port = port
                    this.domain_resolver = domainResolver
                    this.detour = detour
                }
            }
            s.startsWith("h3://", ignoreCase = true) -> {
                val raw = s.removePrefix("h3://")
                val host = if (raw.startsWith("[")) raw.substringAfter("[").substringBefore("]") else raw.substringBefore(":")
                val port = if (raw.contains(":") && !raw.endsWith("]")) raw.substringAfterLast(":").toIntOrNull() else null
                DNSServerOptions().apply {
                    this.type = "h3"
                    this.tag = tag
                    this.server = host
                    if (port != null && port != 443) this.server_port = port
                    this.domain_resolver = domainResolver
                    this.detour = detour
                }
            }
            s.startsWith("udp://", ignoreCase = true) -> {
                val raw = s.removePrefix("udp://")
                val host = if (raw.startsWith("[")) raw.substringAfter("[").substringBefore("]") else raw.substringBefore(":")
                val port = if (raw.contains(":") && !raw.endsWith("]")) raw.substringAfterLast(":").toIntOrNull() else null
                DNSServerOptions().apply {
                    this.type = "udp"
                    this.tag = tag
                    this.server = host
                    if (port != null && port != 53) this.server_port = port
                    this.domain_resolver = domainResolver
                    this.detour = detour
                }
            }
            else -> {
                val host = if (s.startsWith("[")) s.substringAfter("[").substringBefore("]") else s.substringBefore(":")
                val port = if (s.contains(":") && !s.endsWith("]")) s.substringAfterLast(":").toIntOrNull() else null
                DNSServerOptions().apply {
                    this.type = "udp"
                    this.tag = tag
                    this.server = host
                    if (port != null && port != 53) this.server_port = port
                    this.domain_resolver = domainResolver
                    this.detour = detour
                }
            }
        }
    }

}
