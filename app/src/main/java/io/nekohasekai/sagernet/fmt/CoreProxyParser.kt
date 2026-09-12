package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.CoreClient

/** Disposable form projection. Imports and storage retain the authoritative Profile. */
object CoreProxyParser {
    private val schemes = setOf(
        "ss", "socks", "socks4", "socks4a", "socks5", "http", "https",
        "trojan", "tuic", "anytls", "hysteria2", "hy2", "vless", "vmess"
    )

    fun supports(uri: String): Boolean =
        uri.contains("://") && uri.substringBefore("://").lowercase() in schemes

    fun parse(uri: String): AbstractBean = ProfileAdapter.toBean(CoreClient.parseURI(uri))
}
