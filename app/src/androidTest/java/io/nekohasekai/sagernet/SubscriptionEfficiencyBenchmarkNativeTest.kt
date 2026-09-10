package io.nekohasekai.sagernet

import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.CoroutineScope
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Identical baseline/candidate harness. Timed scope: actual HTTP -> complete production
 * doUpdate -> success callback. Fixture setup, metrics reads and Room verification are outside.
 * Args: subscriptionBenchSize=1000, subscriptionBenchWarmups=2, subscriptionBenchRepeats=5,
 * subscriptionBenchFormats=LINKS,UNIVERSAL,CLASH. Every round owns and deletes its group.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionEfficiencyBenchmarkNativeTest {
    // Keep the suspend lambda type at Kotlin call sites; Java selects the legacy JVM
    // runBlocking(context, block) method retained by both baseline and current runtime.
    private fun <T> legacyRunBlocking(block: suspend CoroutineScope.() -> T): T =
        LegacyCoroutineBridge.runBlocking(block)

    @get:Rule val selectionState = org.junit.rules.RuleChain.outerRule(BenchmarkForegroundRule())
        .around(ProfileSelectionStateRule())

    private data class Fixture(val body: String, val names: List<String>, val changedNames: Set<String>)
    private data class Counters(val changed: Int, val added: List<String>, val updated: Map<String, String>,
                                val deleted: List<String>, val duplicate: List<String>)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun rssKb(): Long = File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()
            ?.substringBefore(' ')?.toLongOrNull() ?: -1L
    }
    private fun stats(): Map<String, Long> = Debug.getRuntimeStats().mapNotNull { (key, value) ->
        value?.toLongOrNull()?.let { key to it }
    }.toMap()

    private fun fixture(format: String, size: Int, changed: Boolean, reversed: Boolean): Fixture {
        val records = mutableListOf<Pair<String, String>>()
        val changedNames = linkedSetOf<String>()
        repeat(size) { index ->
            val name = "bench-$index"
            val port = if (changed && index < 10) 1443 else 443
            val payload = when (format) {
                "LINKS" -> "trojan://synthetic@node$index.example:$port#${name}"
                "UNIVERSAL" -> SOCKSBean().apply {
                    initializeDefaultValues(); this.name = name
                    serverAddress = "node$index.example"; serverPort = port
                    username = "synthetic"; password = "fixture"
                }.toUniversalLink()
                "CLASH" -> "  - {type: ${listOf("socks5", "http", "trojan", "anytls")[index % 4]}, name: $name, server: node$index.example, port: $port, username: synthetic, password: fixture}"
                else -> error("Unknown benchmark format: $format")
            }
            records.add(name to payload)
            if (changed && index < 10) changedNames.add(name)
            // At most one duplicate per base name: realistic duplicate links without quadratic naming.
            if (format == "UNIVERSAL" && index % 100 == 99) records.add("$name (1)" to payload)
        }
        val ordered = if (reversed) records.asReversed() else records
        // Reversal swaps identical pair positions; disambiguated output still starts with base name.
        val names = if (format == "UNIVERSAL" && reversed) ordered.map { (name, _) ->
            when {
                name.endsWith(" (1)") -> name.removeSuffix(" (1)")
                name.substringAfter("bench-").toInt() % 100 == 99 -> "$name (1)"
                else -> name
            }
        } else ordered.map { it.first }
        return Fixture((if (format == "CLASH") "proxies:\n" else "") + ordered.joinToString("\n") { it.second }, names, changedNames)
    }

    @Test fun completeHttpUpdatePersistenceSamples() = legacyRunBlocking {
        val args = InstrumentationRegistry.getArguments()
        val size = args.getString("subscriptionBenchSize")?.toInt() ?: 1000
        val warmups = args.getString("subscriptionBenchWarmups")?.toInt() ?: 2
        val repeats = args.getString("subscriptionBenchRepeats")?.toInt() ?: 5
        val formats = (args.getString("subscriptionBenchFormats") ?: "LINKS,UNIVERSAL,CLASH").split(',')
        require(size >= 10 && warmups >= 0 && repeats > 0)
        check(DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Idle ||
            DataStore.serviceState == io.nekohasekai.sagernet.bg.BaseService.State.Stopped) {
            "Benchmark requires an idle VPN service"
        }
        val db = SagerDatabase.instance
        val beforeGroups = db.groupDao().allGroups().map { it.id }.toSet()
        LoopbackHttpFixture().use { server ->
            for (format in formats) {
                // Serialization/compression of synthetic fixtures is never part of the measurements.
                val initial = fixture(format, size, false, false)
                val changed = fixture(format, size, true, false)
                val reordered = fixture(format, size, true, true)
                val phases = listOf("first" to initial, "unchanged" to initial,
                    "few_changed" to changed, "order_changed" to reordered)
                val expectedDigests = mutableMapOf<String, String>()
                repeat(warmups + repeats) { round ->
                    val sub = SubscriptionBean().apply {
                        initializeDefaultValues(); link = "http://127.0.0.1:${server.port}/benchmark"
                        forceResolve = false; deduplication = false
                    }
                    val group = ProxyGroup(name = "Subscription efficiency benchmark", type = GroupType.SUBSCRIPTION, subscription = sub)
                    group.id = db.groupDao().createGroup(group)
                    DataStore.selectedProxy = 0L
                    DataStore.selectedGroup = group.id
                    var counters: Counters? = null
                    val ui = object : GroupManager.Interface {
                        override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) {
                            counters = Counters(changed, added, updated, deleted, duplicate)
                        }
                        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) = error(message)
                        override suspend fun confirm(message: String) = error("Unexpected confirmation")
                        override suspend fun alert(message: String) = error("Unexpected alert")
                    }
                    try {
                        var firstIds = emptyMap<String, Long>()
                        for ((phase, content) in phases) {
                            server.reply.set(LoopbackHttpFixture.Reply(body = content.body))
                            counters = null
                            val requestsBefore = server.requests.get()
                            val rssBefore = rssKb()
                            val nativeBefore = Debug.getNativeHeapAllocatedSize()
                            val statsBefore = stats()
                            val cpuBefore = Process.getElapsedCpuTime()
                            val threadBefore = Debug.threadCpuTimeNanos()
                            val start = System.nanoTime()
                            RawUpdater.doUpdate(group, sub, ui, false)
                            val elapsed = System.nanoTime() - start
                            val threadCpu = Debug.threadCpuTimeNanos() - threadBefore
                            val cpu = Process.getElapsedCpuTime() - cpuBefore
                            val statsAfter = stats()
                            val nativeAfter = Debug.getNativeHeapAllocatedSize()
                            val rssAfter = rssKb()
                            val result = checkNotNull(counters)
                            val rows = db.proxyDao().getByGroup(group.id)
                            assertEquals(content.names, rows.map { it.displayName() })
                            assertEquals((1L..rows.size.toLong()).toList(), rows.map { it.userOrder })
                            assertEquals(1, server.requests.get() - requestsBefore)
                            assertTrue(result.deleted.isEmpty()); assertTrue(result.duplicate.isEmpty())
                            val expectedChanged = when (phase) { "first" -> rows.size; "few_changed" -> 10; else -> 0 }
                            assertEquals(expectedChanged, result.changed)
                            assertEquals(if (phase == "first") content.names else emptyList<String>(), result.added)
                            assertEquals(if (phase == "few_changed") content.changedNames else emptySet<String>(), result.updated.keys)
                            if (phase == "first") {
                                firstIds = rows.associate { it.displayName() to it.id }
                                assertEquals(rows.first().id, DataStore.selectedProxy)
                            } else assertEquals(firstIds, rows.associate { it.displayName() to it.id })
                            assertEquals(firstIds.getValue(initial.names.first()), DataStore.selectedProxy)
                            assertTrue(db.groupDao().getById(group.id)!!.subscription!!.lastUpdated > 0)
                            // Stable DB projection includes complete Bean storage, ordering and counters,
                            // excludes allocated row/group IDs and subscription.lastUpdated wall time.
                            val semantic = JsonObject().apply {
                                add("rows", JsonArray().apply { rows.forEach { row -> add(JsonObject().apply {
                                    addProperty("type", row.type); addProperty("order", row.userOrder)
                                    addProperty("tx", row.tx); addProperty("rx", row.rx)
                                    addProperty("status", row.status); addProperty("ping", row.ping)
                                    addProperty("uuid", row.uuid); addProperty("error", row.error)
                                    add("bean", gson.toJsonTree(row.requireBean()))
                                }) } })
                                add("counters", gson.toJsonTree(result))
                                addProperty("selected_name", rows.firstOrNull { it.id == DataStore.selectedProxy }?.displayName())
                                addProperty("http_requests", server.requests.get() - requestsBefore)
                            }
                            val digest = hash(semantic.toString().toByteArray())
                            expectedDigests[phase]?.let { assertEquals("Semantic drift: $format/$phase", it, digest) }
                            expectedDigests[phase] = digest
                            val report = JsonObject().apply {
                                addProperty("schema", 1); addProperty("format", format); addProperty("phase", phase)
                                addProperty("size", size); addProperty("rows", rows.size)
                                addProperty("iteration", round - warmups); addProperty("warmup", round < warmups)
                                addProperty("fixture_sha256", hash(content.body.toByteArray()))
                                addProperty("semantic_sha256", digest)
                                addProperty("elapsed_ns", elapsed); addProperty("process_cpu_ms", cpu)
                                addProperty("thread_cpu_ns", threadCpu)
                                addProperty("rss_before_kb", rssBefore); addProperty("rss_after_kb", rssAfter)
                                addProperty("native_before_bytes", nativeBefore); addProperty("native_after_bytes", nativeAfter)
                                add("runtime_stat_deltas", JsonObject().apply {
                                    statsAfter.toSortedMap().forEach { (key, value) ->
                                        statsBefore[key]?.let { addProperty(key, value - it) }
                                    }
                                })
                                add("counters", gson.toJsonTree(result).asJsonObject.apply {
                                    // Keep log lines small; the digest above includes full names/maps.
                                    addProperty("added_count", result.added.size); remove("added")
                                    addProperty("updated_count", result.updated.size); remove("updated")
                                    addProperty("deleted_count", result.deleted.size); remove("deleted")
                                    addProperty("duplicate_count", result.duplicate.size); remove("duplicate")
                                })
                            }
                            Log.i("SubscriptionEfficiency", report.toString())
                        }
                    } finally {
                        db.runInTransaction { db.proxyDao().deleteByGroup(group.id); db.groupDao().deleteById(group.id) }
                    }
                }
            }
        }
        assertEquals(beforeGroups, db.groupDao().allGroups().map { it.id }.toSet())
    }
}
