package io.nekohasekai.sagernet.group

import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal enum class SubscriptionOutcome { UPDATED, SKIPPED, SUPERSEDED, TEMPORARY_FAILURE, PERMANENT_FAILURE }
internal class SubscriptionFailure(val retryable: Boolean, message: String, val code: String = "UNKNOWN") : IOException(message)

internal fun subscriptionFailure(error: Exception): SubscriptionOutcome = when (error) {
    is CancellationException -> throw error
    is SubscriptionRefresh.Stale -> SubscriptionOutcome.SUPERSEDED
    is SubscriptionFailure -> if (error.retryable) SubscriptionOutcome.TEMPORARY_FAILURE else SubscriptionOutcome.PERMANENT_FAILURE
    is java.nio.charset.CharacterCodingException, is SecurityException, is FileNotFoundException, is IllegalArgumentException, is IllegalStateException -> SubscriptionOutcome.PERMANENT_FAILURE
    is IOException -> SubscriptionOutcome.TEMPORARY_FAILURE
    else -> SubscriptionOutcome.PERMANENT_FAILURE
}

internal suspend fun subscriptionFeedback(block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { io.nekohasekai.sagernet.ktx.Logs.w("Subscription feedback failed") }
}
