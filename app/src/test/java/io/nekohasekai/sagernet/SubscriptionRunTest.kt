package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.SubscriptionOutcome
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRunTest {
    private fun group(id: Long) = ProxyGroup(id = id, type = GroupType.SUBSCRIPTION,
        subscription = SubscriptionBean().applyDefaultValues().apply {
            link = "https://example.test/$id"; autoUpdate = true; autoUpdateDelay = 15; lastUpdated = 0
        })
    @Test fun mixedResultsHaveIndependentRetriesAndOnlySuccessChangesSuccessTime() = runBlocking {
        val groups = (1L..4).associateWith(::group)
        val outcomes = listOf(SubscriptionOutcome.UPDATED, SubscriptionOutcome.TEMPORARY_FAILURE,
            SubscriptionOutcome.PERMANENT_FAILURE, SubscriptionOutcome.SUPERSEDED)
        val calls = mutableMapOf<Long, Int>()
        suspend fun run(id: Long): SubscriptionOutcome = SubscriptionRun.execute(id, SubscriptionSchedule.fingerprint(groups[id]!!.subscription!!),
            { 2000 }, { false }, { groups[it] }, { row, _ ->
                calls[row.id] = calls.getOrDefault(row.id, 0) + 1
                outcomes[(row.id-1).toInt()].also { if (it == SubscriptionOutcome.UPDATED) row.subscription!!.lastUpdated = 2000 }
            }, {}, {})
        groups.keys.forEach { id ->
            val result = run(id)
            if (SubscriptionSchedule.retry(result, 0)) {
                val second = run(id)
                if (SubscriptionSchedule.retry(second, 1)) assertFalse(SubscriptionSchedule.retry(run(id), 2))
            }
        }
        assertEquals(mapOf(1L to 1, 2L to 3, 3L to 1, 4L to 1), calls)
        assertEquals(2000, groups[1]!!.subscription!!.lastUpdated)
        for (id in 2L..4) assertEquals(0, groups[id]!!.subscription!!.lastUpdated)
        assertEquals(SubscriptionOutcome.SKIPPED, run(1))
    }
    @Test fun deletedChangedDisabledAndDisconnectedJobsDoNotStartUpdate() = runBlocking {
        var row: ProxyGroup? = group(1)
        val expected = SubscriptionSchedule.fingerprint(row!!.subscription!!)
        suspend fun run() = SubscriptionRun.execute(1, expected, { 2000 }, { false }, { row },
            { _, _ -> error("must not download") }, { error("must not notify") }, { error("must not clear another notification") })
        row!!.subscription!!.link = "content://fixture/switched"
        assertEquals(SubscriptionOutcome.SUPERSEDED, run())
        row = group(1); row!!.subscription!!.autoUpdate = false
        assertEquals(SubscriptionOutcome.SUPERSEDED, run())
        row = null; assertEquals(SubscriptionOutcome.SUPERSEDED, run())
        row = group(1).also { it.subscription!!.updateWhenConnectedOnly = true }
        val connectedOnly = SubscriptionSchedule.fingerprint(row!!.subscription!!)
        assertEquals(SubscriptionOutcome.SKIPPED, SubscriptionRun.execute(1, connectedOnly, {2000}, {false}, {row},
            {_,_->error("must not download")},{},{}))
    }
    @Test fun notificationFailureCannotReplaceCommitOrSkipCleanupOnCancellation() = runBlocking {
        val row = group(1); var cleared = 0
        val expected = SubscriptionSchedule.fingerprint(row.subscription!!)
        val result = SubscriptionRun.execute(1, expected, {2000}, {false}, {row},
            {_,_->SubscriptionOutcome.UPDATED}, {throw SecurityException("no notification permission")}, {cleared++;error("notification failure")})
        assertEquals(SubscriptionOutcome.UPDATED,result);assertEquals(1,cleared)
        val entered=CompletableDeferred<Unit>()
        val job=launch { SubscriptionRun.execute(1,expected,{2000},{false},{row},
            {_,_->entered.complete(Unit);awaitCancellation()},{},{cleared++}) }
        entered.await();job.cancelAndJoin();assertEquals(2,cleared)
    }
    @Test fun overlappingAttemptsOfSameWorkCannotClearEachOthersNotification() = runBlocking {
        val row = group(1)
        val expected = SubscriptionSchedule.fingerprint(row.subscription!!)
        val workId = java.util.UUID.randomUUID()
        val oldTag = SubscriptionRun.notificationTag(workId)
        val newTag = SubscriptionRun.notificationTag(workId)
        val visible = mutableSetOf<String>()
        val oldStarted = CompletableDeferred<Unit>()
        val oldStopping = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val old = launch {
            SubscriptionRun.execute(1, expected, {2000}, {false}, {row}, {_,_->
                oldStarted.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { oldStopping.complete(Unit); releaseOld.await() }
                }
            }, { visible.add(oldTag) }, { visible.remove(oldTag) })
        }
        oldStarted.await(); old.cancel(); oldStopping.await()
        val current = launch {
            SubscriptionRun.execute(1, expected, {2000}, {false}, {row}, {_,_->
                newStarted.complete(Unit); releaseNew.await(); SubscriptionOutcome.UPDATED
            }, { visible.add(newTag) }, { visible.remove(newTag) })
        }
        newStarted.await(); releaseOld.complete(Unit); old.join()
        assertEquals(setOf(newTag), visible)
        releaseNew.complete(Unit); current.join(); assertTrue(visible.isEmpty())
    }

}
