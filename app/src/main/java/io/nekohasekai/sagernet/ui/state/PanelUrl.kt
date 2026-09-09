package io.nekohasekai.sagernet.ui.state

import java.net.URI

object PanelUrl {
    fun isValid(value: String): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }

    /** WebView canonicalizes host case, default ports, and an empty root path. */
    fun sameTarget(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        return try {
            val a = URI(URI(first).toASCIIString())
            val b = URI(URI(second).toASCIIString())
            fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b) &&
                a.rawUserInfo == b.rawUserInfo && a.rawPath.ifEmpty { "/" } == b.rawPath.ifEmpty { "/" } &&
                a.rawQuery == b.rawQuery && a.rawFragment == b.rawFragment
        } catch (_: Exception) { false }
    }

}
