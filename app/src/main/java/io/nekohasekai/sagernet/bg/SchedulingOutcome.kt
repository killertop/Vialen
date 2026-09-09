package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException

/** Scheduling follows persistence: its failure is reported separately, cancellation still propagates. */
internal suspend fun reportSchedulingFailure(
    operation: suspend () -> Unit,
    report: suspend (Exception) -> Unit,
) {
    try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(e)
    }
}
