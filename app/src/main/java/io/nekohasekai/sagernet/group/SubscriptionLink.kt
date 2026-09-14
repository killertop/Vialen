package io.nekohasekai.sagernet.group

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object SubscriptionLink {
    fun normalize(raw: String): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "请填写订阅链接" }
        require(value.none { it.isWhitespace() || it == '\\' } &&
            Regex("(?i)^https?://").containsMatchIn(value)) { "订阅链接格式不正确" }
        val url = value.toHttpUrlOrNull()
        require(url != null && url.port in 1..65535 && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()) {
            "请输入 http 或 https 订阅链接"
        }
        // Replace only the authority host; retain the exact path/query encoding and token.
        val authorityStart = value.indexOf("://") + 3
        val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: value.length
        val authority = value.substring(authorityStart, authorityEnd)
        require(authority.isNotEmpty()) { "订阅链接格式不正确" }
        require('@' !in authority) { "订阅链接不能包含账号密码" }
        val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1
            else authority.indexOf(':').takeIf { it >= 0 } ?: authority.length
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return url.scheme + "://" + host + authority.substring(hostEnd) + value.substring(authorityEnd)
    }
}
