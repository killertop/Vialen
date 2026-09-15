package io.nekohasekai.sagernet.group

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SubscriptionResponseTest {
    @Test fun temporaryFailuresKeepRetryPolicyAndDistinctChineseMessages() {
        val codes = listOf("TIMEOUT", "DNS_FAILED", "NETWORK_ERROR", "RATE_LIMITED", "SERVER_ERROR")
        val messages = codes.map { code ->
            val e = failure(code)
            assertTrue(e.retryable)
            assertEquals(SubscriptionOutcome.TEMPORARY_FAILURE, subscriptionFailure(e))
            assertEquals(code, e.code)
            e.message!!
        }
        assertEquals(codes.size, messages.toSet().size)
        assertTrue(messages.all { it.any { c -> c in '\u4e00'..'\u9fff' } })
    }
    @Test fun permanentFailuresDoNotRetryOrExposeUnknownCode() {
        for (code in listOf("ACCESS_DENIED", "NOT_FOUND", "HTTP_REJECTED", "TOO_LARGE", "TLS_REJECTED", "synthetic-private")) {
            val e = failure(code)
            assertFalse(e.retryable)
            assertFalse(e.message!!.contains("synthetic-private"))
        }
    }
    @Test fun successAndCancellationRemainSeparate() {
        checkSubscriptionResponse("OK")
        assertThrows(CancellationException::class.java) { checkSubscriptionResponse("CANCELLED") }
    }
    private fun failure(code: String): SubscriptionFailure =
        assertThrows(SubscriptionFailure::class.java) { checkSubscriptionResponse(code) }
}
