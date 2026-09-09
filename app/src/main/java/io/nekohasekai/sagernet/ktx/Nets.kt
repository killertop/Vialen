@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.fmt.AbstractBean
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

fun linkBuilder() = HttpUrl.Builder().scheme("https")

fun HttpUrl.Builder.toLink(scheme: String, appendDefaultPort: Boolean = true): String {
    var url = build()
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    var replace = false
    if (appendDefaultPort && url.port == defaultPort) {
        url = url.newBuilder().port(14514).build()
        replace = true
    }
    return url.toString().replace("${url.scheme}://", "$scheme://").let {
        if (replace) it.replace(":14514", ":$defaultPort") else it
    }
}

fun String.isIpAddress(): Boolean {
    return NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)
}

fun String.isIpAddressV6(): Boolean {
    return NGUtil.isIpv6Address(this)
}

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

fun AbstractBean.wrapUri(): String {
    return "${finalAddress.wrapIPV6Host()}:$finalPort"
}

fun mkPort(): Int {
    val socket = Socket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(0))
    val port = socket.localPort
    socket.close()
    return port
}

const val USER_AGENT = "Vialen/Android/" + BuildConfig.VERSION_NAME + " (Prefer ClashMeta Format)"

/** Release an I/O resource without masking an earlier failure with a close error. */
fun Closeable.closeQuietly() {
    try {
        close()
    } catch (failure: RuntimeException) {
        throw failure
    } catch (_: Exception) {
        // Match the former OkHttp helper without depending on its internal API.
    }
}

/** Preserve Android 10/11 Conscrypt's documented harmless SSL close failure. */
fun Socket.closeQuietly() {
    try {
        close()
    } catch (failure: RuntimeException) {
        if (failure.message != "bio == null") throw failure
    } catch (_: Exception) {
        // Checked close errors must not replace the connection result.
    }
}
