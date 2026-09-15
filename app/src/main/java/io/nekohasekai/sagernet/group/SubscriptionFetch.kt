package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.RunningServiceSnapshot
import io.nekohasekai.sagernet.ktx.USER_AGENT
import kotlinx.coroutines.*
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes the native request immediately; the scope joins its IO child before returning. */
internal object SubscriptionFetch {
    data class Result(val text: String, val userinfo: String, val disposition: String)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fetch(link: String, userAgent: String): Result = coroutineScope {
        val client = Libcore.newHttpClient().apply {
            // A newly saved port applies to the next connection. Until then use the
            // live listener, not a port at which nothing is listening yet.
            trySocks5(RunningServiceSnapshot.read(SagerNet.application)?.mixedPort ?: DataStore.mixedPort)
            tryH3Direct()
            modernTLS()
        }
        val request = client.newRequest()
        try {
            request.setURL(link)
            request.setUserAgent(userAgent.ifBlank { USER_AGENT })
            request.setResponseSizeLimit(16L * 1024 * 1024)
            request.setTimeoutMillis(30_000)
            if (DataStore.allowInsecureOnRequest) request.allowInsecure()
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { request.cancel() }
                // ATOMIC guarantees cleanup even if cancellation races dispatcher entry.
                launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    try {
                        ensureActive()
                        val response = request.executeSubscription()
                        when (response.code) {
                            "OK" -> Unit
                            "CANCELLED" -> throw CancellationException("订阅更新已取消")
                            "TEMPORARY", "TIMEOUT" -> throw SubscriptionFailure(true, "暂时无法更新，请稍后重试", response.code)
                            "TOO_LARGE" -> throw SubscriptionFailure(false, "订阅文件过大", response.code)
                            "TLS_REJECTED" -> throw SubscriptionFailure(false, "订阅证书无效，请检查链接")
                            else -> throw SubscriptionFailure(false, "订阅访问被拒绝，请检查链接或权限")
                        }
                        val text = response.content.decodeToString(throwOnInvalidSequence = true)
                        val result = Result(text, response.userinfo, response.disposition)
                        continuation.resume(result)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    } finally {
                        request.cancel()
                        client.close()
                    }
                }
            }
        } catch (error: Throwable) {
            request.cancel()
            client.close()
            throw error
        }
    }
}
