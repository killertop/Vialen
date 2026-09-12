package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataStore
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
            trySocks5(DataStore.mixedPort)
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
                        val response = request.execute()
                        val text = response.content.decodeToString(throwOnInvalidSequence = true)
                        val result = Result(text, Util.getStringBox(response.getHeader("Subscription-Userinfo")),
                            Util.getStringBox(response.getHeader("Content-Disposition")))
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
