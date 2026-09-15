package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.TrafficData

/** Per-looper display history, accessed by its single sampling coroutine. */
internal class TrafficChanges {
    private var subscription = Long.MIN_VALUE
    private val previous = HashMap<Long, Pair<Long, Long>>()
    fun next(rows: List<TrafficData>, subscriptionVersion: Long): List<TrafficData> {
        val initial = subscription != subscriptionVersion
        subscription = subscriptionVersion
        val changes = rows.filter { row ->
            val value = row.tx to row.rx
            val changed = previous.put(row.id, value) != value
            initial || changed
        }
        previous.keys.retainAll(rows.mapTo(HashSet()) { it.id })
        return changes
    }
    companion object { const val MAX_ROWS_PER_CALLBACK = 256 }
}
