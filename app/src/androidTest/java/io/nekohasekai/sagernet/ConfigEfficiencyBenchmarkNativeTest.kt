package io.nekohasekai.sagernet

import android.os.Debug
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.oracle.buildLegacyConfig
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * End-to-end full-flow comparison:
 * Same-APK Production Pipeline (buildConfig: Room capture + snapshot + JNI + Rust generation + decode)
 * versus a frozen legacy orchestration (buildLegacyConfig: Room traversal + SingBoxOptions graph + Gson serialization).
 *
 * Scope of implementation:
 * - The measured fixture uses SOCKS entities, whose legacy outbound builder
 *   (buildSingBoxOutboundSocksBean) is pure Kotlin object construction. This bounds the
 *   comparison to this fixture; generic legacy protocol builders are outside its attribution scope.
 * - BenchmarkForegroundRule observes RESUMED state before/after test execution to mitigate OEM background
 *   freezing, but does not guarantee total absence of scheduling interference.
 * - Alternating ABBA execution order per iteration to mitigate thermal and JIT ordering bias.
 * - Exact unrounded integer metrics (ns, bytes) retained for every sample to support independent recomputation.
 * - Full metadata equivalence asserts config AST, externalIndex, mainEntId, selectorGroupId, profileTagMap,
 *   and trafficMap before and during evaluation.
 */
@RunWith(AndroidJUnit4::class)
class ConfigEfficiencyBenchmarkNativeTest {

    @get:org.junit.Rule
    val state = org.junit.rules.RuleChain.outerRule(BenchmarkForegroundRule())
        .around(ProfileSelectionStateRule())

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun rssKb(): Long = File("/proc/self/status").useLines { rows ->
        rows.firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1L
    }

    /**
     * Within-path stability fingerprint: checks that repeated builds of the same implementation
     * produce identical output across warmups and measured samples. Not cross-path identity
     * because Gson and serde_json format JSON strings with differing whitespace and key ordering.
     */
    private fun withinPathStabilityFingerprint(result: ConfigBuildResult): String {
        val obj = JsonObject().apply {
            addProperty("config", result.config)
            addProperty("main", result.mainEntId)
            addProperty("selector_group", result.selectorGroupId)
            add("tags", gson.toJsonTree(result.profileTagMap.entries.map { listOf(it.key, it.value) }))
            add("traffic", gson.toJsonTree(result.trafficMap.entries.map { e -> listOf(e.key, e.value.map { it.id }) }))
            add("external_index", gson.toJsonTree(result.externalIndex.map { it.chain.mapValues { (_, v) -> v.id } }))
        }
        return hash(obj.toString())
    }

    private data class Sample(
        val iteration: Int,
        val path: String, // "BASELINE_KOTLIN" or "CANDIDATE_RUST"
        val order: Int, // 0 for first in iteration, 1 for second in iteration
        val elapsedNs: Long,
        val allocatedBytes: Long,
        val gcCount: Long,
        val processCpuMs: Long,
        val threadCpuNs: Long,
        val rssKb: Long,
        val nativeBytes: Long,
        val configBytes: Int,
        val runStabilitySha256: String,
    )

    private fun median(sorted: List<Double>): Double {
        require(sorted.isNotEmpty())
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun p90(sorted: List<Double>): Double {
        require(sorted.isNotEmpty())
        val index = kotlin.math.ceil(sorted.size * 0.90).toInt() - 1
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }

    private fun assertFullMetadataEquivalence(expected: ConfigBuildResult, actual: ConfigBuildResult, label: String) {
        val expJson = JsonParser.parseString(expected.config)
        val actJson = JsonParser.parseString(actual.config)
        assertEquals("$label: Config JSON mismatch", expJson, actJson)
        assertEquals("$label: mainEntId mismatch", expected.mainEntId, actual.mainEntId)
        assertEquals("$label: selectorGroupId mismatch", expected.selectorGroupId, actual.selectorGroupId)
        assertEquals("$label: profileTagMap mismatch", expected.profileTagMap, actual.profileTagMap)
        assertEquals("$label: profileTagMap key order mismatch",
            expected.profileTagMap.keys.toList(), actual.profileTagMap.keys.toList())
        assertEquals("$label: trafficMap keys mismatch",
            expected.trafficMap.keys.toList(), actual.trafficMap.keys.toList())
        assertEquals("$label: trafficMap entities mismatch",
            expected.trafficMap.mapValues { (_, v) -> v.map { it.id } },
            actual.trafficMap.mapValues { (_, v) -> v.map { it.id } })
        assertEquals("$label: externalIndex size mismatch", expected.externalIndex.size, actual.externalIndex.size)
        expected.externalIndex.forEachIndexed { i, expItem ->
            val actItem = actual.externalIndex[i]
            assertEquals("$label: externalIndex[$i] chain mismatch",
                expItem.chain.mapValues { (_, v) -> v.id },
                actItem.chain.mapValues { (_, v) -> v.id })
        }
    }

    @Test
    fun testConfigSemanticEquivalenceAndMetadata() {
        val db = SagerDatabase.instance
        val oldRemote = DataStore.remoteDns
        val oldDirect = DataStore.directDns
        val customDao = PublicDatabase.instance.keyValuePairDao()
        val oldCustom = customDao[Key.GLOBAL_CUSTOM_CONFIG]?.let { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        }
        try {
            DataStore.remoteDns = "local"
            DataStore.directDns = "local"
            DataStore.globalCustomConfig = ""

            for (sharedChain in listOf(false, true)) {
                for (size in listOf(1, 100, 1000)) {
                    val group = ProxyGroup(name = "Equivalence group s=$sharedChain n=$size", isSelector = true)
                    group.id = db.groupDao().createGroup(group)
                    val dependencies = ProxyGroup(name = "Equivalence deps s=$sharedChain n=$size")
                    dependencies.id = db.groupDao().createGroup(dependencies)
                    try {
                        fun node(index: Int, groupId: Long): ProxyEntity {
                            val row = ProxyEntity(groupId = groupId).apply {
                                putBean(SOCKSBean().applyDefaultValues().apply {
                                    name = "bench-$index"
                                    serverAddress = "127.0.0.1"
                                    serverPort = 1080 + index % 100
                                })
                            }
                            row.id = db.proxyDao().addProxy(row)
                            return row
                        }

                        val nodes = mutableListOf<ProxyEntity>()
                        db.runInTransaction {
                            val hops = if (sharedChain) {
                                listOf(node(1001, dependencies.id), node(1002, dependencies.id))
                            } else emptyList()

                            repeat(size) { index ->
                                if (sharedChain) {
                                    val row = ProxyEntity(groupId = group.id).apply {
                                        putBean(ChainBean().applyDefaultValues().apply {
                                            name = "bench-chain-$index"
                                            proxies = hops.map { it.id }.toMutableList()
                                        })
                                    }
                                    row.id = db.proxyDao().addProxy(row)
                                    nodes.add(row)
                                } else {
                                    nodes.add(node(index, group.id))
                                }
                            }
                        }

                        val selected = nodes.first()
                        val legacy = buildLegacyConfig(selected)
                        val prod = buildConfig(selected)

                        assertFullMetadataEquivalence(legacy, prod, "EquivalenceTest s=$sharedChain n=$size")

                        val logMsg = "SEMANTIC_EQUIVALENCE_PASS: sharedChain=$sharedChain size=$size configBytes=${legacy.config.toByteArray().size}"
                        Log.i("ConfigEfficiency", logMsg)
                        println(logMsg)
                    } finally {
                        db.runInTransaction {
                            db.proxyDao().deleteByGroup(group.id)
                            db.groupDao().deleteById(group.id)
                            db.proxyDao().deleteByGroup(dependencies.id)
                            db.groupDao().deleteById(dependencies.id)
                        }
                    }
                }
            }
        } finally {
            DataStore.remoteDns = oldRemote
            DataStore.directDns = oldDirect
            if (oldCustom == null) customDao.delete(Key.GLOBAL_CUSTOM_CONFIG) else customDao.put(oldCustom)
        }
    }

    @Test
    fun measureActualBuildConfigComparison() {
        val db = SagerDatabase.instance
        val oldRemote = DataStore.remoteDns
        val oldDirect = DataStore.directDns
        val customDao = PublicDatabase.instance.keyValuePairDao()
        val oldCustom = customDao[Key.GLOBAL_CUSTOM_CONFIG]?.let { row ->
            KeyValuePair(row.key).also { it.valueType = row.valueType; it.value = row.value.copyOf() }
        }

        val args = InstrumentationRegistry.getArguments()
        val warmups = args.getString("configBenchWarmups")?.toInt() ?: 2
        val repeats = args.getString("configBenchRepeats")?.toInt() ?: 10
        val sizes = args.getString("configBenchSizes")?.split(",")
            ?.map {
                val s = it.trim()
                require(s.isNotEmpty() && s.all { c -> c.isDigit() } && s.toInt() > 0) { "Invalid size: '$it'" }
                s.toInt()
            } ?: listOf(1, 100, 1000)

        require(warmups > 0 && repeats > 0) { "warmups and repeats must be positive" }

        try {
            DataStore.remoteDns = "local"
            DataStore.directDns = "local"
            DataStore.globalCustomConfig = ""

            for (sharedChain in listOf(false, true)) {
                for (size in sizes) {
                    val group = ProxyGroup(name = "Config efficiency bench s=$sharedChain n=$size", isSelector = true)
                    group.id = db.groupDao().createGroup(group)
                    val dependencies = ProxyGroup(name = "Config efficiency deps s=$sharedChain n=$size")
                    dependencies.id = db.groupDao().createGroup(dependencies)

                    try {
                        fun node(index: Int, groupId: Long): ProxyEntity {
                            val row = ProxyEntity(groupId = groupId).apply {
                                putBean(SOCKSBean().applyDefaultValues().apply {
                                    name = "bench-$index"
                                    serverAddress = "127.0.0.1"
                                    serverPort = 1080 + index % 100
                                })
                            }
                            row.id = db.proxyDao().addProxy(row)
                            return row
                        }

                        val nodes = mutableListOf<ProxyEntity>()
                        db.runInTransaction {
                            val hops = if (sharedChain) {
                                listOf(node(1001, dependencies.id), node(1002, dependencies.id))
                            } else emptyList()

                            repeat(size) { index ->
                                if (sharedChain) {
                                    val row = ProxyEntity(groupId = group.id).apply {
                                        putBean(ChainBean().applyDefaultValues().apply {
                                            name = "bench-chain-$index"
                                            proxies = hops.map { it.id }.toMutableList()
                                        })
                                    }
                                    row.id = db.proxyDao().addProxy(row)
                                    nodes.add(row)
                                } else {
                                    nodes.add(node(index, group.id))
                                }
                            }
                        }

                        val selected = nodes.first()

                        // Verify full metadata equivalence once outside timing before measurement
                        val preLegacy = buildLegacyConfig(selected)
                        val preProd = buildConfig(selected)
                        assertFullMetadataEquivalence(preLegacy, preProd, "PreBenchmarkCheck s=$sharedChain n=$size")

                        val expectedLegacyConfig = preLegacy.config
                        val expectedProdConfig = preProd.config

                        // Warmup
                        repeat(warmups) {
                            val wLegacy = buildLegacyConfig(selected)
                            val wProd = buildConfig(selected)
                            assertEquals(expectedLegacyConfig, wLegacy.config)
                            assertEquals(expectedProdConfig, wProd.config)
                        }

                        val baselineSamples = mutableListOf<Sample>()
                        val candidateSamples = mutableListOf<Sample>()

                        fun measureCandidate(iteration: Int, order: Int): Sample {
                            val allocBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
                            val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
                            val nativeBefore = Debug.getNativeHeapAllocatedSize()
                            val rssBefore = rssKb()
                            val cpuBefore = Process.getElapsedCpuTime()
                            val threadBefore = Debug.threadCpuTimeNanos()
                            val start = System.nanoTime()

                            val result = buildConfig(selected)

                            val elapsed = System.nanoTime() - start
                            val threadCpu = Debug.threadCpuTimeNanos() - threadBefore
                            val cpu = Process.getElapsedCpuTime() - cpuBefore
                            val rssAfter = rssKb()
                            val nativeAfter = Debug.getNativeHeapAllocatedSize()
                            val allocAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
                            val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
                            val allocated = if (allocBefore >= 0 && allocAfter >= 0) allocAfter - allocBefore else -1L
                            val collections = if (gcBefore >= 0 && gcAfter >= 0) gcAfter - gcBefore else -1L

                            assertEquals(expectedProdConfig, result.config)
                            assertEquals(group.id, result.selectorGroupId)
                            assertFullMetadataEquivalence(preProd, result, "PostRunCandidate s=$sharedChain n=$size it=$iteration")

                            return Sample(
                                iteration = iteration,
                                path = "CANDIDATE_RUST",
                                order = order,
                                elapsedNs = elapsed,
                                allocatedBytes = allocated,
                                gcCount = collections,
                                processCpuMs = cpu,
                                threadCpuNs = threadCpu,
                                rssKb = rssAfter,
                                nativeBytes = nativeAfter,
                                configBytes = result.config.toByteArray().size,
                                runStabilitySha256 = withinPathStabilityFingerprint(result)
                            )
                        }

                        fun measureBaseline(iteration: Int, order: Int): Sample {
                            val allocBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
                            val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
                            val nativeBefore = Debug.getNativeHeapAllocatedSize()
                            val rssBefore = rssKb()
                            val cpuBefore = Process.getElapsedCpuTime()
                            val threadBefore = Debug.threadCpuTimeNanos()
                            val start = System.nanoTime()

                            val result = buildLegacyConfig(selected)

                            val elapsed = System.nanoTime() - start
                            val threadCpu = Debug.threadCpuTimeNanos() - threadBefore
                            val cpu = Process.getElapsedCpuTime() - cpuBefore
                            val rssAfter = rssKb()
                            val nativeAfter = Debug.getNativeHeapAllocatedSize()
                            val allocAfter = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
                            val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
                            val allocated = if (allocBefore >= 0 && allocAfter >= 0) allocAfter - allocBefore else -1L
                            val collections = if (gcBefore >= 0 && gcAfter >= 0) gcAfter - gcBefore else -1L

                            assertEquals(expectedLegacyConfig, result.config)
                            assertEquals(group.id, result.selectorGroupId)
                            assertFullMetadataEquivalence(preLegacy, result, "PostRunBaseline s=$sharedChain n=$size it=$iteration")

                            return Sample(
                                iteration = iteration,
                                path = "BASELINE_KOTLIN",
                                order = order,
                                elapsedNs = elapsed,
                                allocatedBytes = allocated,
                                gcCount = collections,
                                processCpuMs = cpu,
                                threadCpuNs = threadCpu,
                                rssKb = rssAfter,
                                nativeBytes = nativeAfter,
                                configBytes = result.config.toByteArray().size,
                                runStabilitySha256 = withinPathStabilityFingerprint(result)
                            )
                        }

                        val jsonlFile = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "config-benchmark-raw.jsonl")
                        if (sharedChain == false && size == sizes.first()) {
                            jsonlFile.parentFile?.mkdirs()
                            jsonlFile.writeText("") // truncate at start of suite
                        }

                        // Alternating ABBA execution order per iteration
                        val scenarioName = if (sharedChain) "shared_chain" else "direct"
                        fun recordAndLogSample(s: Sample) {
                            val sampleObj = JsonObject().apply {
                                addProperty("schema", 2)
                                addProperty("type", "sample")
                                addProperty("scenario", scenarioName)
                                addProperty("shared_chain", sharedChain)
                                addProperty("size", size)
                                addProperty("iteration", s.iteration)
                                addProperty("path", s.path)
                                addProperty("order", s.order)
                                addProperty("elapsed_ns", s.elapsedNs)
                                addProperty("allocated_bytes", s.allocatedBytes)
                                addProperty("gc_count", s.gcCount)
                                addProperty("process_cpu_ms", s.processCpuMs)
                                addProperty("thread_cpu_ns", s.threadCpuNs)
                                addProperty("rss_kb", s.rssKb)
                                addProperty("native_bytes", s.nativeBytes)
                                addProperty("config_bytes", s.configBytes)
                                addProperty("stability_sha256", s.runStabilitySha256)
                            }
                            val line = sampleObj.toString()
                            Log.i("ConfigEfficiencySample", line)
                            println("CONFIG_SAMPLE: $line")
                            jsonlFile.appendText(line + "\n")
                        }

                        repeat(repeats) { iteration ->
                            if (iteration % 2 == 0) {
                                val b = measureBaseline(iteration, 0)
                                baselineSamples.add(b)
                                recordAndLogSample(b)

                                val c = measureCandidate(iteration, 1)
                                candidateSamples.add(c)
                                recordAndLogSample(c)
                            } else {
                                val c = measureCandidate(iteration, 0)
                                candidateSamples.add(c)
                                recordAndLogSample(c)

                                val b = measureBaseline(iteration, 1)
                                baselineSamples.add(b)
                                recordAndLogSample(b)
                            }
                        }

                        // Compact statistical processing (no giant raw sample arrays in summary)
                        fun stats(samples: List<Sample>): JsonObject {
                            val elapsedMs = samples.map { it.elapsedNs / 1_000_000.0 }.sorted()
                            val allocKb = samples.map { it.allocatedBytes / 1024.0 }.sorted()
                            val threadCpuMs = samples.map { it.threadCpuNs / 1_000_000.0 }.sorted()
                            val gcTotal = samples.sumOf { it.gcCount }
                            val cpuMsTotal = samples.sumOf { it.processCpuMs }
                            return JsonObject().apply {
                                add("elapsed_ms", JsonObject().apply {
                                    addProperty("median", median(elapsedMs))
                                    addProperty("p90", p90(elapsedMs))
                                    addProperty("min", elapsedMs.first())
                                    addProperty("max", elapsedMs.last())
                                })
                                add("allocated_kb", JsonObject().apply {
                                    addProperty("median", median(allocKb))
                                    addProperty("p90", p90(allocKb))
                                    addProperty("min", allocKb.first())
                                    addProperty("max", allocKb.last())
                                })
                                add("thread_cpu_ms", JsonObject().apply {
                                    addProperty("median", median(threadCpuMs))
                                    addProperty("p90", p90(threadCpuMs))
                                })
                                addProperty("gc_total", gcTotal)
                                addProperty("process_cpu_ms_total", cpuMsTotal)
                                addProperty("config_bytes", samples.first().configBytes)
                                addProperty("within_path_stability_sha256", samples.first().runStabilitySha256)
                            }
                        }

                        val baseStats = stats(baselineSamples)
                        val candStats = stats(candidateSamples)

                        val baseElapsedMed = baseStats.getAsJsonObject("elapsed_ms").get("median").asDouble
                        val candElapsedMed = candStats.getAsJsonObject("elapsed_ms").get("median").asDouble
                        val elapsedDiffPct = if (baseElapsedMed > 0.0) ((candElapsedMed - baseElapsedMed) / baseElapsedMed) * 100.0 else 0.0

                        val baseAllocMed = baseStats.getAsJsonObject("allocated_kb").get("median").asDouble
                        val candAllocMed = candStats.getAsJsonObject("allocated_kb").get("median").asDouble
                        val allocDiffPct = if (baseAllocMed > 0.0) ((candAllocMed - baseAllocMed) / baseAllocMed) * 100.0 else 0.0

                        val report = JsonObject().apply {
                            addProperty("schema", 2)
                            addProperty("type", "summary")
                            addProperty("scenario", scenarioName)
                            addProperty("shared_chain", sharedChain)
                            addProperty("size", size)
                            addProperty("repeats", repeats)
                            addProperty("warmups", warmups)
                            addProperty("elapsed_median_diff_pct", "%.2f".format(elapsedDiffPct))
                            addProperty("alloc_median_diff_pct", "%.2f".format(allocDiffPct))
                            add("baseline_kotlin_legacy", baseStats)
                            add("candidate_rust_prod", candStats)
                        }

                        val summaryString = report.toString()
                        Log.i("ConfigEfficiencySummary", summaryString)
                        println("CONFIG_SUMMARY: $summaryString")
                        jsonlFile.appendText(summaryString + "\n")

                    } finally {
                        db.runInTransaction {
                            db.proxyDao().deleteByGroup(group.id)
                            db.groupDao().deleteById(group.id)
                            db.proxyDao().deleteByGroup(dependencies.id)
                            db.groupDao().deleteById(dependencies.id)
                        }
                    }
                }
            }
        } finally {
            DataStore.remoteDns = oldRemote
            DataStore.directDns = oldDirect
            if (oldCustom == null) customDao.delete(Key.GLOBAL_CUSTOM_CONFIG) else customDao.put(oldCustom)
        }
    }
}
