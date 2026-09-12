package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    private class Fixture {
        var time = 0L
        var probes = 0
        var resets = 0
        var networkEnabled = true
        var wakeEnabled = true
        var health = ConnectionRecovery.Health.Failed
        var probeBody: suspend () -> ConnectionRecovery.Health = { health }
        var pauseBody: suspend (Long) -> Unit = {}
        var resetBody: suspend () -> Unit = {}
        val events = mutableListOf<ConnectionRecovery.Event>()
        val controller = ConnectionRecovery(
            Dispatchers.Unconfined, { time }, { probes++; probeBody() }, { resets++; resetBody() },
            { networkEnabled }, { wakeEnabled }, { pauseBody(it) },
            { events += it },
        )
        fun network(id: Long) = controller.networkChanged(ConnectionRecovery.NetworkIdentity(id, "wlan0"))
        fun start() { network(1); controller.connected() }
    }

    @Test fun baselineAndDuplicateCallbacksDoNothingButSameInterfaceNewIdentityProbes() = runBlocking {
        val f = Fixture(); f.start()
        repeat(20) { f.network(1) }
        assertEquals(0, f.probes)
        f.network(2)
        assertEquals(3, f.probes)
        assertEquals(1, f.resets)
        repeat(20) { f.network(2) }
        assertEquals(3, f.probes)
        f.controller.stop(); f.controller.join()
    }

    @Test fun offlineDoesNotProbeAndSameNetworkReturningDoes() = runBlocking {
        val f = Fixture(); f.start()
        f.controller.networkChanged(null)
        assertEquals(0, f.probes)
        f.network(1)
        assertEquals(1, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun healthyOrUnavailableNeverResets() = runBlocking {
        for (health in listOf(ConnectionRecovery.Health.Healthy, ConnectionRecovery.Health.Unavailable)) {
            val f = Fixture(); f.health = health; f.start(); f.network(2)
            assertEquals(1, f.probes); assertEquals(0, f.resets)
            f.controller.stop(); f.controller.join()
        }
    }

    @Test fun transientFailureThenSuccessDoesNotReset() = runBlocking {
        val f = Fixture()
        f.probeBody = { if (f.probes == 1) ConnectionRecovery.Health.Failed else ConnectionRecovery.Health.Healthy }
        f.start(); f.network(2)
        assertEquals(2, f.probes); assertEquals(0, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun resetCooldownAndLifetimeBudgetBoundPersistentFailure() = runBlocking {
        val f = Fixture(); f.start(); f.network(2); f.network(3)
        assertEquals(1, f.resets)
        for (id in 4L..12L) { f.time += 60_000; f.network(id) }
        assertEquals(3, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun rapidSwitchesCancelSettlingAndProbeOnlyLatestNetwork() = runBlocking {
        val f = Fixture(); val waits = mutableListOf<CompletableDeferred<Unit>>()
        f.pauseBody = { CompletableDeferred<Unit>().also { waits += it }.await() }
        f.health = ConnectionRecovery.Health.Healthy
        f.start(); f.network(2); f.network(3); f.network(4)
        assertEquals(0, f.probes)
        waits.forEach { it.complete(Unit) }
        assertEquals(1, f.probes); assertEquals(0, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun stopCancelsActiveProbeAndRejectsLateCallbacks() = runBlocking {
        val f = Fixture(); var cancelled = false
        f.probeBody = { try { awaitCancellation() } finally { cancelled = true } }
        f.start(); f.network(2); f.controller.stop(); f.controller.join()
        assertTrue(cancelled)
        f.network(3); f.controller.screenChanged(false); f.time += 60_000; f.controller.screenChanged(true)
        assertEquals(1, f.probes); assertEquals(0, f.resets)
    }

    @Test fun offlineCancelsActiveProbeWithoutReset() = runBlocking {
        val f = Fixture(); var cancelled = false
        f.probeBody = { try { awaitCancellation() } finally { cancelled = true } }
        f.start(); f.network(2); f.controller.networkChanged(null)
        assertTrue(cancelled); assertEquals(0, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun screenAndDozeCoalesceAndShortScreenToggleDoesNothing() = runBlocking {
        val f = Fixture(); f.start()
        f.controller.screenChanged(false); f.time += 1_000; f.controller.screenChanged(true)
        assertEquals(0, f.probes)
        f.controller.screenChanged(false); f.controller.idleChanged(true)
        f.time += 30_000; f.network(2)
        f.controller.screenChanged(true)
        assertEquals(0, f.probes)
        f.controller.idleChanged(false)
        repeat(20) { f.controller.screenChanged(true); f.controller.idleChanged(false) }
        assertEquals(3, f.probes); assertEquals(1, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun existingGatesRemainEffective() = runBlocking {
        val f = Fixture(); f.networkEnabled = false; f.wakeEnabled = false; f.start(); f.network(2)
        f.controller.screenChanged(false); f.time += 60_000; f.controller.screenChanged(true)
        assertEquals(0, f.probes); assertEquals(0, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun screenOffNetworkChangeImmediatelyUsesNetworkGateEvenWhenWakeGateIsOff() = runBlocking {
        val f = Fixture(); f.wakeEnabled = false; f.start()
        f.controller.screenChanged(false); f.network(2); f.time += 1_000
        assertEquals(3, f.probes)
        f.controller.screenChanged(true)
        assertEquals(1, f.resets)
        assertEquals(ConnectionRecovery.Reason.Network, f.events.first().reason)
        f.controller.stop(); f.controller.join()
    }

    @Test fun pureWakeWithWakeGateOffDoesNothing() = runBlocking {
        val f = Fixture(); f.wakeEnabled = false; f.start()
        f.controller.screenChanged(false); f.time += 60_000; f.controller.screenChanged(true)
        assertEquals(0, f.probes)
        f.controller.stop(); f.controller.join()
    }

    @Test fun networkChangesDuringStartupAreRecoveredAfterConnected() = runBlocking {
        val f = Fixture(); f.network(1); f.network(2)
        assertEquals(0, f.probes)
        f.controller.connected()
        assertEquals(1, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun firstNetworkAfterConnectedIsTreatedAsOfflineRecovery() = runBlocking {
        val f = Fixture(); f.controller.connected(); f.network(1)
        assertEquals(1, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun idleInterruptingNetworkRecoveryRetainsItsReason() = runBlocking {
        val f = Fixture(); f.wakeEnabled = false
        val waiting = CompletableDeferred<Unit>()
        f.pauseBody = { waiting.await() }
        f.start(); f.network(2); f.controller.idleChanged(true)
        f.pauseBody = {}; f.controller.idleChanged(false)
        assertEquals(1, f.resets)
        assertTrue(f.events.any { it.kind == ConnectionRecovery.Kind.Cancel })
        f.controller.stop(); f.controller.join()
    }

    @Test fun idleExitWhileScreenOffRecoversPendingNetworkEvenWithWakeDisabled() = runBlocking {
        val f = Fixture(); f.wakeEnabled = false; f.start()
        f.controller.screenChanged(false); f.controller.idleChanged(true); f.network(2)
        assertEquals(0, f.probes)
        f.controller.idleChanged(false)
        assertEquals(3, f.probes); assertEquals(1, f.resets)
        f.time += 60_000; f.controller.screenChanged(true)
        assertEquals(3, f.probes)
        f.controller.stop(); f.controller.join()
    }

    @Test fun screenOffDoesNotCancelActiveNetworkProbe() = runBlocking {
        val f = Fixture(); val result = CompletableDeferred<ConnectionRecovery.Health>()
        f.probeBody = { result.await() }; f.start(); f.network(2)
        f.controller.screenChanged(false)
        assertFalse(f.events.any { it.kind == ConnectionRecovery.Kind.Cancel })
        result.complete(ConnectionRecovery.Health.Healthy)
        assertEquals(1, f.probes); assertEquals(0, f.resets)
        f.controller.stop(); f.controller.join()
    }

    @Test fun eventEvidenceRecordsBothFailuresResetAndPostResetResult() = runBlocking {
        val f = Fixture(); f.start(); f.network(2)
        assertEquals(listOf(ConnectionRecovery.Kind.Scheduled, ConnectionRecovery.Kind.ProbeResult,
            ConnectionRecovery.Kind.ProbeResult, ConnectionRecovery.Kind.Reset,
            ConnectionRecovery.Kind.PostResetResult), f.events.map { it.kind })
        assertEquals(1, f.events.last().resetCount)
        assertEquals(ConnectionRecovery.Health.Failed, f.events.last().health)
        f.controller.stop(); f.controller.join()
    }

    @Test fun replacementProbeAndStopWaitForCancelledWorkerExit() = runBlocking {
        val f = Fixture(); val exit = CompletableDeferred<Unit>(); var exited = false
        f.probeBody = {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { exit.await(); exited = true }
            }
        }
        f.start(); f.network(2); f.network(3)
        assertEquals(1, f.probes) // The replacement must wait behind the cancelled worker.
        f.controller.stop()
        val joined = async(start = CoroutineStart.UNDISPATCHED) { f.controller.join() }
        assertFalse(joined.isCompleted)
        exit.complete(Unit); joined.await()
        assertTrue(exited); assertEquals(1, f.probes); assertEquals(0, f.resets)
    }

    @Test fun endpointValidationRejectsCredentialsTokensAndInvalidSchemes() {
        for (url in listOf("https://user:pass@example.com/", "https://example.com/?token=secret",
            "https://example.com/#secret", "file:///tmp/test", "https:///missing-host", "not a url")) {
            assertNull(ConnectionRecovery.probeTarget(url))
        }
        assertEquals("http://cp.cloudflare.com/", ConnectionRecovery.probeTarget("http://cp.cloudflare.com/"))
        assertEquals("https://example.com/generate_204", ConnectionRecovery.probeTarget("https://example.com/generate_204"))
    }

    @Test fun screenOffProbeThenAnotherLongSleepStillChecksOnWake() = runBlocking {
        val f = Fixture(); f.health = ConnectionRecovery.Health.Healthy; f.start()
        f.controller.screenChanged(false); f.time = 40_000; f.network(2)
        f.time += 30_000; f.controller.screenChanged(true)
        assertEquals(2, f.probes)
        assertEquals(ConnectionRecovery.Reason.Wake, f.events.last().reason)
        f.controller.stop(); f.controller.join()
    }

    @Test fun immediateWakeAfterCompletedNetworkProbeDoesNotRepeatIt() = runBlocking {
        val f = Fixture(); f.health = ConnectionRecovery.Health.Healthy; f.start()
        f.controller.screenChanged(false); f.time = 60_000; f.network(2)
        f.time += 1; f.controller.screenChanged(true)
        assertEquals(1, f.probes)
        f.controller.stop(); f.controller.join()
    }

    @Test fun wakeWhileNetworkProbeRunsDoesNotCancelOrDuplicateIt() = runBlocking {
        val f = Fixture(); val result = CompletableDeferred<ConnectionRecovery.Health>()
        f.probeBody = { result.await() }; f.start(); f.controller.screenChanged(false)
        f.network(2); f.time = 60_000; f.controller.screenChanged(true)
        assertEquals(1, f.probes)
        assertFalse(f.events.any { it.kind == ConnectionRecovery.Kind.Cancel })
        result.complete(ConnectionRecovery.Health.Healthy)
        assertEquals(1, f.probes)
        f.controller.stop(); f.controller.join()
    }

    @Test fun stopJoinsActualBlockingResetBeforeCallerCanCloseBox() = runBlocking {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val exited = java.util.concurrent.atomic.AtomicBoolean(false)
        var closed = false
        f.resetBody = {
            withContext(Dispatchers.IO) {
                entered.complete(Unit)
                try { release.await() } finally { exited.set(true) }
            }
        }
        try {
            f.start(); f.network(2); entered.await()
            f.controller.stop()
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
                f.controller.join()
                assertTrue(exited.get())
                closed = true
            }
            assertFalse(cleanup.isCompleted); assertFalse(closed); assertFalse(exited.get())
            release.countDown(); cleanup.await()
            assertTrue(closed)
            assertEquals(2, f.probes) // Cancellation skips the post-reset probe.
            assertFalse(f.events.any { it.kind == ConnectionRecovery.Kind.PostResetResult })
        } finally {
            release.countDown(); f.controller.stop(); f.controller.join()
        }
    }
}
