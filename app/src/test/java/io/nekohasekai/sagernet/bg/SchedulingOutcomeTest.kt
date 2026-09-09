package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException

class SchedulingOutcomeTest {
    @Test fun scheduleTimeoutIsReportedWithoutUndoingSuccessfulPersistence() = runBlocking {
        val failure = TimeoutException("fixture")
        var reports = 0
        reportSchedulingFailure(operation = { throw failure }, report = {
            assertSame(failure, it)
            reports++
        })
        assertEquals(1, reports)
    }

    @Test fun callerCancellationPropagatesWithoutFailureNotification() = runBlocking {
        val cancellation = CancellationException("caller left")
        var reports = 0
        try {
            reportSchedulingFailure(operation = { throw cancellation }, report = { reports++ })
            fail("Cancellation must propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertEquals(0, reports)
    }

    @Test fun successfulSchedulingDoesNotReportFailure() = runBlocking {
        var reports = 0
        reportSchedulingFailure(operation = {}, report = { reports++ })
        assertEquals(0, reports)
    }
}
