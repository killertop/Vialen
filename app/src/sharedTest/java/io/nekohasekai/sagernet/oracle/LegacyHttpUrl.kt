package io.nekohasekai.sagernet.oracle

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.IDN
import java.net.URLDecoder

/** Keep the frozen parser oracle's OkHttp alpha / java.net.IDN host contract.
 * OkHttp 5 stable changed to nontransitional IDNA; using that policy here would
 * silently change the reference parser when only the dependency is upgraded.
 */
internal fun String.toLegacyHttpUrl(): HttpUrl = legacyIdnaInput().toHttpUrl()

internal fun String.toLegacyHttpUrlOrNull(): HttpUrl? =
    try { toLegacyHttpUrl() } catch (_: IllegalArgumentException) { null }

private fun String.legacyIdnaInput(): String {
    val input = trim { it <= ' ' }
    val start = input.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return input
    val end = input.indexOfAny(charArrayOf('/', '\\', '?', '#'), start)
        .let { if (it < 0) input.length else it }
    val authority = input.substring(start, end)
    val hostStart = authority.lastIndexOf('@') + 1
    val address = authority.substring(hostStart)
    if (address.startsWith('[')) return input // IPv6 has no IDNA mapping.
    val portStart = address.lastIndexOf(':').let { if (it < 0) address.length else it }
    val encodedHost = address.substring(0, portStart)
    // OkHttp percent-decodes host escapes without treating '+' as a space.
    val host = URLDecoder.decode(encodedHost.replace("+", "%2B"), "UTF-8")
    require(host.isNotEmpty() && host.none { it <= ' ' || it == '\u007f' || it in "#%/:?@[\\]" }) {
        "Invalid URL host"
    }
    val asciiHost = IDN.toASCII(host)
    return input.substring(0, start) + authority.substring(0, hostStart) +
        asciiHost + address.substring(portStart) + input.substring(end)
}
