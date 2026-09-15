package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.group.SubscriptionOutcome
import kotlinx.coroutines.CancellationException

/** One periodic occurrence owns exactly one group and its notification. */
internal object SubscriptionRun {
    // A constraint stop can overlap cleanup with a new attempt of the same WorkSpec.
    fun notificationTag(workId: java.util.UUID) = "subscription-run:$workId:${java.util.UUID.randomUUID()}"
    suspend fun execute(id: Long, expected: String, now: () -> Long, connected: () -> Boolean,
        load: suspend (Long) -> ProxyGroup?, update: suspend (ProxyGroup, String) -> SubscriptionOutcome,
        show: (ProxyGroup) -> Unit, clear: () -> Unit): SubscriptionOutcome {
        val group = load(id) ?: return SubscriptionOutcome.SUPERSEDED
        if (group.type != GroupType.SUBSCRIPTION) return SubscriptionOutcome.SUPERSEDED
        val bean = group.subscription ?: return SubscriptionOutcome.SUPERSEDED
        if (SubscriptionSchedule.fingerprint(bean) != expected) return SubscriptionOutcome.SUPERSEDED
        if (!SubscriptionSchedule.due(bean, connected(), now())) return SubscriptionOutcome.SKIPPED
        try {
            feedback { show(group) }
            return update(group, expected)
        } finally { feedback(clear) }
    }
    private fun feedback(block: () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Notification delivery is not a database outcome. */ }
    }
}
