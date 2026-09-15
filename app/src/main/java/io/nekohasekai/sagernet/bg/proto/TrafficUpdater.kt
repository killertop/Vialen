package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock

class TrafficUpdater(
    private val queryStats: (String, String) -> Long,
    val items: List<TrafficLooperData>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val queryBatch: ((String) -> ByteArray)? = null,
) {
    class TrafficLooperData(
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
    )

    private data class Sample(val tx: Long, val rx: Long, val txRate: Long, val rxRate: Long)
    private val samples = HashMap<String, Sample>()

    init { val now = clock(); items.forEach { it.lastUpdate = now } }

    fun resetRate(item: TrafficLooperData) {
        item.lastUpdate = clock()
        item.txRate = 0
        item.rxRate = 0
    }

    /** Caller serializes sampling with selection and shutdown. Query each resetting counter once. */
    fun updateAll() {
        val now = clock()
        samples.clear()
        val tags = items.filterNot { it.ignore }.map { it.tag }.distinct()
        val batch = queryBatch?.let { query ->
            if (tags.isEmpty()) emptyMap() else {
                val bytes = query(tags.joinToString("\n"))
                require(bytes.size == tags.size * 16) { "Invalid traffic snapshot size" }
                val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                tags.associateWith { buffer.long to buffer.long }
            }
        }
        items.forEach { item ->
            if (item.ignore) return@forEach
            val sample = samples.getOrPut(item.tag) {
                val tx = batch?.getValue(item.tag)?.first ?: queryStats(item.tag, "uplink")
                val rx = batch?.getValue(item.tag)?.second ?: queryStats(item.tag, "downlink")
                val elapsed = now - item.lastUpdate
                // Still drain bytes when selection/stop occurs in the same millisecond.
                Sample(tx, rx, if (elapsed > 0) tx * 1000 / elapsed else 0,
                    if (elapsed > 0) rx * 1000 / elapsed else 0)
            }
            item.tx += sample.tx
            item.rx += sample.rx
            item.txRate = sample.txRate
            item.rxRate = sample.rxRate
            item.lastUpdate = now
        }
    }
}
