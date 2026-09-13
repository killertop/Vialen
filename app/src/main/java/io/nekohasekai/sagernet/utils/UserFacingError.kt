package io.nekohasekai.sagernet.utils

/** UI boundary: never expose URLs, credentials, stack traces or exception class names. */
object UserFacingError {
    fun describe(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        return describe(chain.joinToString(" ") { it.message.orEmpty() }, chain.any {
            it is java.net.SocketTimeoutException
        })
    }

    fun describe(raw: String, timeout: Boolean = false): String {
        val text = raw.lowercase()
        return when {
            timeout || listOf("timeout", "timed out", "deadline").any(text::contains) -> "请求超时，请重试"
            Regex("(?:http|status|code)[^0-9]{0,15}(401|403)\\b").containsMatchIn(text) -> "访问被拒绝，请检查链接或账号"
            Regex("(?:http|status|code)[^0-9]{0,15}404\\b").containsMatchIn(text) -> "资源不存在，请检查链接"
            Regex("(?:http|status|code)[^0-9]{0,15}429\\b").containsMatchIn(text) -> "请求过多，请稍后重试"
            Regex("(?:http|status|code)[^0-9]{0,15}5[0-9]{2}\\b").containsMatchIn(text) -> "服务器暂不可用，请稍后重试"
            listOf("certificate", "x509", "ssl", "tls handshake").any(text::contains) -> "安全连接失败，请检查证书和时间"
            listOf("unknownhost", "no such host", "unable to resolve", "name resolution").any(text::contains) -> "域名解析失败，请检查网络"
            listOf("network is unreachable", "network unreachable", "no route to host").any(text::contains) -> "网络不可用，请检查连接"
            listOf("refused", "reset by peer", "broken pipe", "closed pipe").any(text::contains) -> "连接失败，请检查节点或稍后重试"
            listOf("permission denied", "not permitted", "securityexception").any(text::contains) -> "权限不足，请在系统设置中授权"
            listOf("no space", "disk full").any(text::contains) -> "存储空间不足，请清理后重试"
            listOf("too large", "exceeds", "size limit", "32 mib").any(text::contains) -> "内容过大，请换用较小的文件或订阅"
            listOf("no proxies", "no profiles", "empty response").any(text::contains) -> "未找到可用节点，请检查订阅内容"
            listOf("rule-set", "rule set", "rule_set").any(text::contains) -> "规则文件无效，请重新下载"
            listOf("invalid port", "port range").any(text::contains) -> "端口无效，请填写 1～65535"
            listOf("invalid", "malformed", "parse", "decode", "unexpected", "unsupported").any(text::contains) -> "内容格式不正确或暂不支持"
            text.contains("missing selected profile") -> "请先选择一个节点"
            text.contains("core not started") -> "连接未启动，请重试"
            raw.length in 1..80 && raw.any { it in '\u4e00'..'\u9fff' } &&
                !raw.contains("://") && !raw.contains('\n') -> raw
            else -> "操作失败，请检查配置后重试"
        }
    }
}
