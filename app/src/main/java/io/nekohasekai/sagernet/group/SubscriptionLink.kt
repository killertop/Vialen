package io.nekohasekai.sagernet.group

import java.net.URI

internal object SubscriptionLink {
    fun normalize(raw: String, existing: String? = null): String {
        val value = raw.trim()
        require(value.isNotEmpty()) { "请填写订阅链接" }
        // Preserve existing document subscriptions without accepting new file access URLs.
        if (value == existing && value.startsWith("content://")) return value
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("订阅链接格式不正确") }
        require(uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && (uri.port == -1 || uri.port in 1..65535)) {
            "请输入 http 或 https 订阅链接"
        }
        return value
    }
}
