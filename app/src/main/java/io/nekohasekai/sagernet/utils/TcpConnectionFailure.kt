package io.nekohasekai.sagernet.utils

import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Classify original exceptions before applying user-facing translations. */
internal enum class TcpConnectionFailure {
    TIMEOUT, REFUSED, UNREACHABLE, OTHER;
    companion object {
        fun classify(error: Throwable): TcpConnectionFailure {
            val chain = generateSequence(error) { it.cause }.take(8).toList()
            val raw = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return when {
                chain.any { it is SocketTimeoutException } || "etimedout" in raw || "timed out" in raw -> TIMEOUT
                "econnrefused" in raw || "connection refused" in raw -> REFUSED
                chain.any { it is NoRouteToHostException || it is UnknownHostException } ||
                    listOf("enetunreach", "ehostunreach", "network is unreachable", "no route to host").any(raw::contains) -> UNREACHABLE
                else -> OTHER
            }
        }
    }
}
