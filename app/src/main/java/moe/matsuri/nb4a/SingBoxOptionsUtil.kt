package moe.matsuri.nb4a

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
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

fun normalizeRuleSetTag(input: String): String {
    return when {
        input.startsWith("geosite:") -> "geosite-" + input.removePrefix("geosite:").lowercase()
        input.startsWith("geoip:") -> "geoip-" + input.removePrefix("geoip:").lowercase()
        input.startsWith("geosite-") || input.startsWith("geoip-") -> input.lowercase()
        input.startsWith("http://") || input.startsWith("https://") -> {
            val base = input.substringAfterLast("/").substringBefore("?")
            if (base.startsWith("geosite-") || base.startsWith("geoip-") || base == "geosite" || base == "geoip") {
                "user-$base"
            } else {
                base
            }
        }
        else -> input.lowercase()
    }
}

fun SingBoxOptions.DNSRule_DefaultOptions.makeSingBoxRule(list: List<String>) {
    rule_set = mutableListOf<String>()
    domain = mutableListOf<String>()
    domain_suffix = mutableListOf<String>()
    domain_regex = mutableListOf<String>()
    domain_keyword = mutableListOf<String>()
    list.forEach {
        if (it.startsWith("geosite:") || it.startsWith("geosite-")) {
            rule_set.plusAssign(normalizeRuleSetTag(it))
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else if (it.startsWith("http://") || it.startsWith("https://")) {
            rule_set.plusAssign(normalizeRuleSetTag(it))
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.DNSRule_DefaultOptions.checkEmpty(): Boolean {
    if (rule_set?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (ip_is_private == true) return false
    if (ip_cidr?.isNotEmpty() == true) return false
    if (match_response != null) return false
    return true
}

fun generateRuleSet(ruleSetString: List<String>, ruleSet: MutableList<RuleSet>) {
    ruleSetString.forEach { item ->
        when {
            item.startsWith("geoip:") || item.startsWith("geoip-") -> {
                val cat = if (item.startsWith("geoip:")) item.removePrefix("geoip:").lowercase() else item.removePrefix("geoip-").lowercase()
                if (cat != "private") {
                    val tag = "geoip-$cat"
                    ruleSet.add(RuleSet().apply {
                        type = "remote"
                        this.tag = tag
                        format = "binary"
                        url = "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-$cat.srs"
                        http_client = "default-http-client"
                        update_interval = "24h"
                    })
                }
            }

            item.startsWith("geosite:") || item.startsWith("geosite-") -> {
                val cat = if (item.startsWith("geosite:")) item.removePrefix("geosite:").lowercase() else item.removePrefix("geosite-").lowercase()
                val tag = "geosite-$cat"
                ruleSet.add(RuleSet().apply {
                    type = "remote"
                    this.tag = tag
                    format = "binary"
                    url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-$cat.srs"
                    http_client = "default-http-client"
                    update_interval = "24h"
                })
            }

            item.startsWith("http://") || item.startsWith("https://") -> {
                val tag = normalizeRuleSetTag(item)
                ruleSet.add(RuleSet().apply {
                    type = "remote"
                    this.tag = tag
                    format = if (item.contains(".srs")) "binary" else "source"
                    url = item
                    http_client = "default-http-client"
                })
            }
        }
    }
}

fun SingBoxOptions.Rule_DefaultOptions.makeSingBoxRule(list: List<String>, isIP: Boolean) {
    if (isIP) {
        ip_cidr = mutableListOf<String>()
        rule_set = mutableListOf<String>()
    } else {
        rule_set = mutableListOf<String>()
        domain = mutableListOf<String>()
        domain_suffix = mutableListOf<String>()
        domain_regex = mutableListOf<String>()
        domain_keyword = mutableListOf<String>()
    }
    list.forEach {
        if (isIP) {
            if (it.startsWith("geoip:") || it.startsWith("geoip-")) {
                if (it == "geoip:private" || it == "geoip-private") {
                    ip_is_private = true
                } else {
                    rule_set.plusAssign(normalizeRuleSetTag(it))
                }
            } else if (it.startsWith("http://") || it.startsWith("https://")) {
                rule_set.plusAssign(normalizeRuleSetTag(it))
            } else {
                ip_cidr.plusAssign(it)
            }
            return@forEach
        }
        if (it.startsWith("geosite:") || it.startsWith("geosite-") || it.startsWith("http://") || it.startsWith("https://")) {
            rule_set.plusAssign(normalizeRuleSetTag(it))
        } else if (it.startsWith("full:")) {
            domain.plusAssign(it.removePrefix("full:").lowercase())
        } else if (it.startsWith("domain:")) {
            domain_suffix.plusAssign(it.removePrefix("domain:").lowercase())
        } else if (it.startsWith("regexp:")) {
            domain_regex.plusAssign(it.removePrefix("regexp:").lowercase())
        } else if (it.startsWith("keyword:")) {
            domain_keyword.plusAssign(it.removePrefix("keyword:").lowercase())
        } else {
            domain_suffix.plusAssign(it.lowercase())
        }
    }
    ip_cidr?.removeIf { it.isNullOrBlank() }
    rule_set?.removeIf { it.isNullOrBlank() }
    domain?.removeIf { it.isNullOrBlank() }
    domain_suffix?.removeIf { it.isNullOrBlank() }
    domain_regex?.removeIf { it.isNullOrBlank() }
    domain_keyword?.removeIf { it.isNullOrBlank() }
    if (ip_cidr?.isEmpty() == true) ip_cidr = null
    if (rule_set?.isEmpty() == true) rule_set = null
    if (domain?.isEmpty() == true) domain = null
    if (domain_suffix?.isEmpty() == true) domain_suffix = null
    if (domain_regex?.isEmpty() == true) domain_regex = null
    if (domain_keyword?.isEmpty() == true) domain_keyword = null
}

fun SingBoxOptions.Rule_DefaultOptions.checkEmpty(): Boolean {
    if (ip_cidr?.isNotEmpty() == true) return false
    if (domain?.isNotEmpty() == true) return false
    if (rule_set?.isNotEmpty() == true) return false
    if (domain_suffix?.isNotEmpty() == true) return false
    if (domain_regex?.isNotEmpty() == true) return false
    if (domain_keyword?.isNotEmpty() == true) return false
    if (user_id?.isNotEmpty() == true) return false
    if (ip_is_private == true) return false
    if (source_ip_is_private == true) return false
    if (rule_set_ip_cidr_match_source == true) return false
    //
    if (port?.isNotEmpty() == true) return false
    if (port_range?.isNotEmpty() == true) return false
    if (source_ip_cidr?.isNotEmpty() == true) return false
    //
    if (!_hack_custom_config.isNullOrBlank()) return false
    return true
}
