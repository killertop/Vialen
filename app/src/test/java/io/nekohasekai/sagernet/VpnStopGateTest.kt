package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.VpnStopGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VpnStopGateTest {
    private fun assertBlocked(gate: VpnStopGate) {
        try {
            gate.checkCanStart()
            fail("Unconfirmed cleanup must deny startup")
        } catch (_: IllegalStateException) { }
    }

    @Test fun lateProofDisposesBeforeUnlockAndForeignGenerationCannotUnlock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = VpnStopGate()
            val lost = CompletableDeferred<Unit>()
            val generation = Any()
            var disposals = 0
            val errors = mutableListOf<Throwable>()
            val job = gate.waitForRemoval(scope, generation, {
                assertBlocked(gate) // State is already published when the worker starts.
                lost.await()
            }, {
                assertBlocked(gate)
                disposals++
            }, { errors.add(it) })
            assertBlocked(gate)
            gate.completeRemoval(Any())
            assertBlocked(gate)
            assertEquals(0, disposals)
            lost.complete(Unit)
            job.join()
            gate.checkCanStart()
            assertEquals(1, disposals)
            assertTrue(errors.isEmpty())

            val nextLost = CompletableDeferred<Unit>()
            val next = gate.waitForRemoval(scope, Any(), { nextLost.await() }, {}, { errors.add(it) })
            gate.completeRemoval(generation)
            assertBlocked(gate)
            nextLost.complete(Unit)
            next.join()
            gate.checkCanStart()
        } finally { scope.cancel() }
    }

    @Test fun fatalFailureCannotBeClearedByLateProof() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = VpnStopGate()
            val lost = CompletableDeferred<Unit>()
            var disposals = 0
            val job = gate.waitForRemoval(scope, Any(), { lost.await() }, { disposals++ }, { throw AssertionError(it) })
            gate.markFailed()
            lost.complete(Unit)
            job.join()
            assertBlocked(gate)
            assertEquals(1, disposals)
        } finally { scope.cancel() }
    }

    @Test fun cancellationAndAlreadyCancelledScopeNeverUnlock() = runBlocking {
        for (cancelBeforeStart in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val gate = VpnStopGate()
            val lost = CompletableDeferred<Unit>()
            var disposals = 0
            val errors = mutableListOf<Throwable>()
            if (cancelBeforeStart) scope.cancel()
            val job = gate.waitForRemoval(scope, Any(), { lost.await() }, { disposals++ }, { errors.add(it) })
            job.cancelAndJoin()
            lost.complete(Unit)
            assertBlocked(gate)
            assertEquals(1, disposals)
            assertEquals(1, errors.size)
            scope.cancel()
        }
    }

    @Test fun disposalFailureIsPermanentAndDisposalIsNotRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = VpnStopGate()
            val denied = IllegalStateException("unregister denied")
            var disposals = 0
            val errors = mutableListOf<Throwable>()
            val job = gate.waitForRemoval(scope, Any(), {}, { disposals++; throw denied }, { errors.add(it) })
            job.join()
            assertBlocked(gate)
            assertEquals(1, disposals)
            assertEquals(listOf(denied), errors)
        } finally { scope.cancel() }
    }

    @Test fun observerFailureCannotBeTreatedAsRemoval() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = VpnStopGate()
            val broken = IllegalStateException("observer disposed")
            val errors = mutableListOf<Throwable>()
            var disposals = 0
            gate.waitForRemoval(scope, Any(), { throw broken }, { disposals++ }, { errors.add(it) }).join()
            assertBlocked(gate)
            assertEquals(1, disposals)
            assertEquals(listOf(broken), errors)
        } finally { scope.cancel() }
    }
    @Test fun cancellationAfterProofButBeforeCompletionCannotUnlock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = VpnStopGate()
        var disposals = 0
        val errors = mutableListOf<Throwable>()
        gate.waitForRemoval(scope, Any(), {
            // Simulate cancellation racing with the final successful observer return.
            scope.cancel()
        }, { disposals++ }, { errors.add(it) }).join()
        assertBlocked(gate)
        assertEquals(1, disposals)
        assertEquals(1, errors.size)
    }

}
