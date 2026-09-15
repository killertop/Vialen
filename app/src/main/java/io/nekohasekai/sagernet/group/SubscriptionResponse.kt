package io.nekohasekai.sagernet.group

import kotlinx.coroutines.CancellationException

/** Stable native codes only; never show server bodies, URLs or transport exception text. */
internal fun checkSubscriptionResponse(code: String) {
    if (code == "OK") return
    if (code == "CANCELLED") throw CancellationException("订阅更新已取消")
    val (retryable, message) = when (code) {
        "TIMEOUT" -> true to "订阅请求超时，请稍后重试"
        "DNS_FAILED" -> true to "无法解析订阅地址，请检查网络"
        "NETWORK_ERROR" -> true to "无法连接订阅，请检查网络"
        "RATE_LIMITED" -> true to "请求过于频繁，请稍后重试"
        "SERVER_ERROR" -> true to "订阅服务暂时异常，请稍后重试"
        "TEMPORARY" -> true to "暂时无法更新，请稍后重试"
        "ACCESS_DENIED" -> false to "订阅访问被拒绝，请检查链接或权限"
        "NOT_FOUND" -> false to "订阅地址不存在，请检查链接"
        "TOO_LARGE" -> false to "订阅文件过大"
        "TLS_REJECTED" -> false to "订阅证书无效，请检查链接"
        else -> false to "订阅请求被拒绝，请检查链接"
    }
    throw SubscriptionFailure(retryable, message, code)
}
