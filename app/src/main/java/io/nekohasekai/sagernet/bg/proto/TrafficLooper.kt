package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
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
    private val profiles = mutableMapOf<Long, ProxyEntity>()
    private var selectedId = -1L
    private var selectedTag = ""
    private val interval = DataStore.speedInterval.toLong()
    private val statistics = DataStore.profileTrafficStatistics
    private val showDirect = DataStore.showDirectSpeed

    /** Install counters before launch returns and before a selector can change. */
    fun start() {
        synchronized(lock) {
            check(job == null && !stopped)
            if (interval <= 0) return
            proxy = checkNotNull(data.proxy)
            idMap[-1] = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)
            val tags = hashSetOf(TAG_PROXY, TAG_BYPASS)
            proxy.config.trafficMap.forEach { (tag, entities) ->
                tags.add(tag)
                entities.forEach { entity ->
                    profiles.putIfAbsent(entity.id, entity)
                    val item = TrafficUpdater.TrafficLooperData(tag = tag, rx = entity.rx,
                        tx = entity.tx, rxBase = entity.rx, txBase = entity.tx,
                        ignore = proxy.config.selectorGroupId >= 0L)
                    idMap[entity.id] = item
                    tagMap[tag] = item
                }
            }
            if (proxy.config.selectorGroupId >= 0L) selectLocked(proxy.config.mainEntId)
            (installStats ?: proxy.box::setV2rayStats)(tags.joinToString("\n"))
            updater = TrafficUpdater(readStats ?: proxy.box::queryStats, idMap.values.toList())
            writer = scope.launch {
                for (id in writes) {
                    val row = synchronized(lock) { profileWithTraffic(id) }
                    if (row != null) ProfileManager.updateProfile(row)
                }
            }
            job = scope.launch { loop() }
        }
    }

    fun onConsumersChanged() { wake.trySend(Unit) }

    fun isSelected(id: Long) = synchronized(lock) { !stopped && (interval <= 0 || selectedId == id) }

    /** Flush the old selection before assigning its shared counter to the new one. */
    fun selectMain(id: Long) = synchronized(lock) {
        if (stopped || id == selectedId || id !in idMap) return@synchronized
        updater?.updateAll()
        val previous = selectedId
        selectLocked(id)
        if (statistics && previous >= 0) writes.trySend(previous)
        wake.trySend(Unit)
    }

    private fun selectLocked(id: Long) {
        val next = idMap[id] ?: return
        idMap[selectedId]?.apply { tag = selectedTag; ignore = true }
        selectedId = id
        selectedTag = next.tag
        next.tag = TAG_PROXY
        next.ignore = false
        updater?.resetRate(next)
    }

    private fun profileWithTraffic(id: Long): ProxyEntity? {
        val item = idMap[id] ?: return null
        val row = profiles[id] ?: return null
        return row.copy(rx = item.rx, tx = item.tx).apply { putBean(row.requireBean()) }
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
                    updater!!.updateAll()
                    idMap.keys.mapNotNull(::profileWithTraffic)
                }
            }
            for (row in final) ProfileManager.updateProfile(row)
            if (final.isNotEmpty()) data.binder.broadcast { callback ->
                final.forEach { callback.cbTrafficUpdate(TrafficData(id = it.id, rx = it.rx, tx = it.tx)) }
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
                if (foreground || statistics) updater?.updateAll()
                if (foreground) displaySnapshot() else null
            }
            if (display != null) data.binder.broadcast { callback ->
                if (data.binder.callbackIdMap[callback] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                    callback.cbSpeedUpdate(display.first)
                    display.second.forEach { callback.cbTrafficUpdate(it) }
                }
            }
            val delay = TrafficSampling.interval(interval, foreground, statistics)
            if (delay == null) wake.receive()
            else withTimeoutOrNull(delay) { wake.receive() }
        }
    }

    private fun displaySnapshot(): Pair<SpeedDisplayData, List<TrafficData>> {
        var txRate = 0L; var rxRate = 0L; var tx = 0L; var rx = 0L
        tagMap.values.forEach {
            if (!it.ignore) { txRate += it.txRate; rxRate += it.rxRate }
            tx += it.tx - it.txBase; rx += it.rx - it.rxBase
        }
        val bypass = checkNotNull(idMap[-1])
        val speed = SpeedDisplayData(txRate, rxRate, if (showDirect) bypass.txRate else 0,
            if (showDirect) bypass.rxRate else 0, tx, rx)
        return speed to if (statistics) idMap.map { (id, item) -> TrafficData(id = id, rx = item.rx, tx = item.tx) } else emptyList()
    }
}

internal object TrafficSampling {
    /** Null means no timer; foreground registration wakes the loop immediately. */
    fun interval(configured: Long, foreground: Boolean, statistics: Boolean): Long? = when {
        configured <= 0 -> null
        foreground -> configured
        statistics -> maxOf(configured, 30_000L)
        else -> null
    }
}
