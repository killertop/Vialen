package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class TrafficLooper internal constructor(
    val data: BaseService.Data,
    private val readStats: ((String, String) -> Long)? = null,
    private val installStats: ((String) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val writes = Channel<Long>(Channel.UNLIMITED)
    private var job: Job? = null
    private var writer: Job? = null
    private var stopped = false
    private lateinit var proxy: ProxyInstance
    private var updater: TrafficUpdater? = null
    private val idMap = mutableMapOf<Long, TrafficUpdater.TrafficLooperData>()
    private val tagMap = mutableMapOf<String, TrafficUpdater.TrafficLooperData>()
    private val persisted = mutableMapOf<Long, TrafficData>()
    private var selectedId = Long.MIN_VALUE // -1 is the bypass counter, never a selection sentinel.
    private val owners = linkedMapOf<String, Set<Long>>()
    private val sampled = mutableMapOf<String, TrafficData>()
    private val statistics = DataStore.profileTrafficStatistics
    private val showDirect = DataStore.showDirectSpeed

    /** Install counters before launch returns and before a selector can change. */
    fun start() {
        synchronized(lock) {
            check(job == null && !stopped)
            proxy = checkNotNull(data.proxy)
            tagMap[TAG_BYPASS] = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)
            tagMap[TAG_PROXY] = TrafficUpdater.TrafficLooperData(tag = TAG_PROXY)
            proxy.config.trafficMap.forEach { (tag, entities) ->
                require(tag != TAG_PROXY && tag != TAG_BYPASS) { "Profile counter must have its own reference tag" }
                tagMap.getOrPut(tag) { TrafficUpdater.TrafficLooperData(tag = tag) }
                owners[tag] = entities.map { it.id }.toSet()
                entities.forEach { entity ->
                    persisted.putIfAbsent(entity.id, TrafficData(entity.id, entity.tx, entity.rx))
                    idMap.getOrPut(entity.id) {
                        TrafficUpdater.TrafficLooperData(tag = "", rx = entity.rx, tx = entity.tx,
                            rxBase = entity.rx, txBase = entity.tx)
                    }
                }
            }
            selectedId = proxy.config.mainEntId
            (installStats ?: proxy.box::setV2rayStats)(tagMap.keys.joinToString("\n"))
            updater = TrafficUpdater(readStats ?: proxy.box::queryStats, tagMap.values.toList())
            writer = scope.launch {
                for (id in writes) {
                    persistTraffic(id)
                }
            }
            job = scope.launch { loop() }
        }
    }

    fun onConsumersChanged() { wake.trySend(Unit) }

    fun isSelected(id: Long) = synchronized(lock) { !stopped && selectedId == id }

    /** Flush the old selection before assigning its shared counter to the new one. */
    fun selectMain(id: Long) = synchronized(lock) {
        if (stopped || id == selectedId || id !in proxy.config.profileTagMap) return@synchronized
        sampleLocked()
        val previousOwners = selectedOwners()
        selectedId = id
        updater?.resetRate(checkNotNull(tagMap[TAG_PROXY]))
        if (statistics) previousOwners.forEach { writes.trySend(it) }
        wake.trySend(Unit)
    }

    private fun selectedOwners(): Set<Long> {
        val tag = proxy.config.profileTagMap[selectedId]
        // Run plans also have a single selector candidate even without a selector group.
        return tag?.let { owners[it] } ?: emptySet()
    }

    /** Native counters reset on read. Sample each tag once, then distribute only
     * its new bytes to every distinct owning row. Shared rows aggregate tags. */
    private fun sampleLocked() {
        updater?.updateAll()
        idMap.values.forEach { it.txRate = 0; it.rxRate = 0 }
        tagMap.forEach { (tag, counter) ->
            val previous = sampled[tag]
            val deltaTx = counter.tx - (previous?.tx ?: 0L)
            val deltaRx = counter.rx - (previous?.rx ?: 0L)
            sampled[tag] = TrafficData(0, counter.tx, counter.rx)
            val targets = if (tag == TAG_PROXY) selectedOwners() else owners[tag].orEmpty()
            targets.forEach { id ->
                val row = checkNotNull(idMap[id])
                row.tx += deltaTx; row.rx += deltaRx
                row.txRate += counter.txRate; row.rxRate += counter.rxRate
            }
        }
    }

    private suspend fun persistTraffic(id: Long): TrafficData? {
        val total = synchronized(lock) {
            val item = idMap[id] ?: return null
            if (id !in persisted) return null
            TrafficData(id, item.tx, item.rx)
        }
        val previous = checkNotNull(persisted[id])
        val delta = TrafficData(id, total.tx - previous.tx, total.rx - previous.rx)
        val saved = ProfileManager.addTraffic(delta)
        // Only the single writer (then stop after join) advances this checkpoint.
        persisted[id] = total
        // The database commit is checkpointed even if notification delivery fails.
        if (saved != null) ProfileManager.postUpdate(saved)
        return saved
    }

    /** Core counters remain readable after close and include final socket bytes. */
    suspend fun stop() {
        synchronized(lock) { stopped = true }
        try {
            job?.cancelAndJoin()
            writes.close()
            writer?.join() // Older selection writes cannot overwrite the final totals.
            val final = synchronized(lock) {
                if (!statistics || updater == null) emptyList() else {
                    sampleLocked()
                    persisted.keys.toList()
                }
            }
            val totals = final.mapNotNull { persistTraffic(it) }
            if (totals.isNotEmpty()) data.binder.broadcast { callback ->
                totals.forEach { callback.cbTrafficUpdate(it) }
            }
        } finally {
            scope.cancel()
            wake.close()
        }
    }

    private fun hasConsumer() = data.state == BaseService.State.Connected &&
        data.binder.callbackIdMap.containsValue(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val foreground = hasConsumer()
            val display = synchronized(lock) {
                if (stopped) return
                if (foreground || statistics) sampleLocked()
                if (foreground) displaySnapshot() else null
            }
            if (display != null) data.binder.broadcast { callback ->
                if (data.binder.callbackIdMap[callback] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                    callback.cbSpeedUpdate(display.first)
                    display.second.forEach { callback.cbTrafficUpdate(it) }
                }
            }
            val delay = TrafficSampling.interval(foreground, statistics)
            if (delay == null) wake.receive()
            else withTimeoutOrNull(delay) { wake.receive() }
        }
    }

    private fun displaySnapshot(): Pair<SpeedDisplayData, List<TrafficData>> {
        var txRate = 0L; var rxRate = 0L; var tx = 0L; var rx = 0L
        tagMap.forEach { (tag, counter) ->
            if (tag != TAG_BYPASS) {
                txRate += counter.txRate; rxRate += counter.rxRate
                tx += counter.tx; rx += counter.rx
            }
        }
        val bypass = checkNotNull(tagMap[TAG_BYPASS])
        val speed = SpeedDisplayData(txRate, rxRate, if (showDirect) bypass.txRate else 0,
            if (showDirect) bypass.rxRate else 0, tx, rx)
        return speed to if (statistics) idMap.map { (id, item) -> TrafficData(id = id, rx = item.rx, tx = item.tx) } else emptyList()
    }
}

internal object TrafficSampling {
    /** Null means no timer; foreground registration wakes the loop immediately. */
    fun interval(foreground: Boolean, statistics: Boolean): Long? = when {
        foreground -> 1_000L
        statistics -> 30_000L
        else -> null
    }
}
