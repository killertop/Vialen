package io.nekohasekai.sagernet

import androidx.work.*
import io.nekohasekai.sagernet.bg.SubscriptionSchedule
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.*
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.MemoryTrimPolicy
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackgroundPolicyTest {
    private fun group(link: String) = ProxyGroup(id = 7, type = GroupType.SUBSCRIPTION,
        subscription = SubscriptionBean().applyDefaultValues().apply {
            this.link = link; autoUpdate = true; autoUpdateDelay = 30; lastUpdated = 0
        })

    @Test fun sourceConstraintsAndConfigIdentityExcludeRemoteMetadata() {
        val web = group("https://example.test/sub")
        val local = group("content://fixture/document")
        assertEquals(NetworkType.CONNECTED, SubscriptionSchedule.request(web, 10000).workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.NOT_REQUIRED, SubscriptionSchedule.request(local, 10000).workSpec.constraints.requiredNetworkType)
        val name = SubscriptionSchedule.name(web)
        web.subscription!!.lastUpdated = 123; web.subscription!!.subscriptionUserinfo = "synthetic"
        assertEquals(name, SubscriptionSchedule.name(web))
        web.subscription!!.link = local.subscription!!.link
        assertNotEquals(name, SubscriptionSchedule.name(web))
        assertFalse(SubscriptionSchedule.name(web).contains("fixture"))
    }
    @Test fun dueRechecksAutoConnectionAndSuccessTimeWithoutWritingFailureTime() {
        val bean = group("https://example.test/sub").subscription!!
        assertTrue(SubscriptionSchedule.due(bean, false, 2000))
        bean.updateWhenConnectedOnly = true
        assertFalse(SubscriptionSchedule.due(bean, false, 2000))
        assertTrue(SubscriptionSchedule.due(bean, true, 2000))
        bean.lastUpdated = 1000
        assertFalse(SubscriptionSchedule.due(bean, true, 2000))
        bean.autoUpdate = false
        assertFalse(SubscriptionSchedule.due(bean, true, 10000))
        assertEquals(1000, bean.lastUpdated)
    }
    @Test fun retryBudgetDoesNotRetryPermanentSkippedOrSupersededOutcomes() {
        SubscriptionOutcome.entries.forEach { outcome ->
            assertEquals(outcome == SubscriptionOutcome.TEMPORARY_FAILURE, SubscriptionSchedule.retry(outcome, 0))
            assertFalse(SubscriptionSchedule.retry(outcome, 2))
        }
        assertTrue(SubscriptionSchedule.retry(SubscriptionOutcome.TEMPORARY_FAILURE, 1))
        assertEquals(SubscriptionOutcome.SUPERSEDED, subscriptionFailure(SubscriptionRefresh.Stale()))
        assertEquals(SubscriptionOutcome.PERMANENT_FAILURE, subscriptionFailure(SecurityException("synthetic")))
        assertEquals(SubscriptionOutcome.PERMANENT_FAILURE, subscriptionFailure(java.io.FileNotFoundException()))
        assertEquals(SubscriptionOutcome.TEMPORARY_FAILURE, subscriptionFailure(java.net.SocketTimeoutException()))
        assertThrows(CancellationException::class.java) { subscriptionFailure(CancellationException()) }
    }
    @Test fun memoryPolicyTreatsHiddenAsLifecycleNotPressureAcrossSDKs() {
        for (sdk in listOf(24, 33, 34, 37)) {
            assertFalse(MemoryTrimPolicy.shouldCollect(sdk, 20, true))
            assertFalse(MemoryTrimPolicy.shouldCollect(sdk, 40, false))
            assertTrue(MemoryTrimPolicy.shouldCollect(sdk, 40, true))
            assertEquals(sdk < 34, MemoryTrimPolicy.shouldCollect(sdk, 15, false))
            assertEquals(sdk < 34, MemoryTrimPolicy.shouldCollect(sdk, 80, false))
            assertFalse(MemoryTrimPolicy.shouldCollect(sdk, 5, true))
        }
    }
}
