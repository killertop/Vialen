package io.nekohasekai.sagernet.ktx

import moe.matsuri.nb4a.utils.NGUtil
import java.io.FileDescriptor
import java.net.Socket
import java.net.URLEncoder

/**
 * Test Source Set Shadow for UtilsKt.
 *
 * Provides Host JVM compatible implementations of extension functions without
 * modifying production runtime reflection semantics in app/src/main/java/.../Utils.kt.
 */

fun String?.blankAsNull(): String? = if (isNullOrBlank()) null else this

val Throwable.readableMessage: String
    get() = localizedMessage.takeIf { !it.isNullOrBlank() } ?: javaClass.simpleName

val Socket.fileDescriptor get() = FileDescriptor()
val FileDescriptor.int get() = -1

fun parsePort(str: String?, default: Int, min: Int = 1025): Int {
    val value = str?.toIntOrNull() ?: default
    return if (value < min || value > 65535) default else value
}

fun String.pathSafe(): String {
    return URLEncoder.encode(this, "UTF-8")
}

fun String.urlSafe(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

fun String.unUrlSafe(): String {
    return NGUtil.urlDecode(this)
}
