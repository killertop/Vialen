package io.nekohasekai.sagernet

import android.os.Debug
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.RustProxyParser
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated proxy parser microbenchmark.
 *
 * SCOPE AND BOUNDARIES:
 * - Measures only URI string parsing and AbstractBean object instantiation.
 * - DOES NOT measure network HTTP fetch, subscription deduplication, Room database
 *   persistence, or profile selection callbacks.
 * - This microbenchmark CANNOT be used to prove end-to-end subscription update benefit.
 */
@RunWith(AndroidJUnit4::class)
class ProductionBatchBenchmarkNativeTest {

    @get:Rule
    val foreground = BenchmarkForegroundRule()

    data class Sample(
        val elapsedMs: Double,
        val threadCpuMs: Double,
        val processCpuMs: Long,
        val javaAllocBytes: Long,
        val nativeDeltaBytes: Long
    )

    private fun allocated() = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L

    @Test
    fun measureActualBeanImportOnAndroid() {
        val sizesArg = InstrumentationRegistry.getArguments().getString("benchmarkSizes")
        val sizes = if (sizesArg == null) {
            listOf(100, 1000, 5000)
        } else {
            require(sizesArg.isNotBlank()) { "benchmarkSizes argument cannot be blank" }
            val parsed = sizesArg.split(',').map { token ->
                val trimmed = token.trim()
                val num = trimmed.toIntOrNull()
                require(num != null && num > 0) {
                    "Invalid benchmarkSizes token '$trimmed' in '$sizesArg'. All sizes must be positive integers."
                }
                num
            }
            require(parsed.isNotEmpty()) { "benchmarkSizes cannot be empty" }
            parsed
        }

        for (size in sizes) {
            val uris = List(size) { "trojan://pass$it@node$it.example:443?type=ws&path=%2Fp&sni=s.example#Node$it" }
            // Precompute Kotlin oracle beans, serialized bytes, and classes outside measurement
            val oracleBeans = uris.map { io.nekohasekai.sagernet.oracle.parseTrojan(it).apply { initializeDefaultValues() } }
            val oracleBytes = oracleBeans.map { KryoConverters.serialize(it) }
            val oracleClasses = oracleBeans.map { it.javaClass }

            val measurements = List(3) { mutableListOf<Sample>() }

            fun run(path: Int): List<AbstractBean> = when (path) {
                0 -> uris.map { io.nekohasekai.sagernet.oracle.parseTrojan(it).apply { initializeDefaultValues() } }
                1 -> uris.map(RustProxyParser::parse)
                else -> RustProxyParser.parseBatch(uris).also {
                    assertEquals(1, it.batchCalls)
                    assertEquals(0, it.singleCalls)
                }.items.map { it.getOrThrow() }
            }

            // Warmup (3 rotating rounds across all 3 paths)
            repeat(3) { round ->
                for (offset in 0..2) {
                    val path = (round + offset) % 3
                    val beans = run(path)
                    assertEquals(size, beans.size)
                }
            }

            // Measured work: 10 rotating rounds (30 executions per size)
            val repeats = 10
            repeat(repeats) { round ->
                for (offset in 0..2) {
                    val path = (round + offset) % 3
                    val beforeAlloc = allocated()
                    val beforeNative = Debug.getNativeHeapAllocatedSize()
                    val beforeThreadCpu = Debug.threadCpuTimeNanos()
                    val beforeProcCpu = Process.getElapsedCpuTime()
                    val start = System.nanoTime()

                    val beans = run(path)

                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    val threadCpu = (Debug.threadCpuTimeNanos() - beforeThreadCpu) / 1_000_000.0
                    val procCpu = Process.getElapsedCpuTime() - beforeProcCpu
                    val afterAlloc = allocated()
                    val afterNative = Debug.getNativeHeapAllocatedSize()

                    measurements[path].add(
                        Sample(
                            elapsedMs = elapsed,
                            threadCpuMs = threadCpu,
                            processCpuMs = procCpu,
                            javaAllocBytes = if (beforeAlloc < 0 || afterAlloc < 0) -1 else afterAlloc - beforeAlloc,
                            nativeDeltaBytes = afterNative - beforeNative
                        )
                    )

                    // Exhaustive semantic correctness check on EVERY bean and EVERY field
                    assertEquals(size, beans.size)
                    for (i in 0 until size) {
                        val b = beans[i]
                        // 1. Exact class match
                        assertEquals(oracleClasses[i], b.javaClass)
                        // 2. Full byte-for-byte Kryo serialization match (covers all transport, TLS, and base fields + name)
                        assertArrayEquals("Kryo serialization mismatch at bean index $i", oracleBytes[i], KryoConverters.serialize(b))
                        // 3. Explicit expected critical fields
                        assertEquals("Node$i", b.name)
                        assertEquals("node$i.example", b.serverAddress)
                        assertEquals(443, b.serverPort)
                        assertTrue("Parsed bean at index $i must be TrojanBean", b is TrojanBean)
                        val trojan = b as TrojanBean
                        assertEquals("pass$i", trojan.password)
                        assertEquals("s.example", trojan.sni)
                        assertEquals("ws", trojan.type)
                        assertEquals("/p", trojan.path)
                        assertEquals("tls", trojan.security)
                        assertFalse(trojan.allowInsecure)
                    }
                }
            }

            fun medianDouble(list: List<Double>): Double {
                val s = list.sorted()
                return if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 else s[s.size / 2]
            }

            fun p90Double(list: List<Double>): Double {
                val s = list.sorted()
                val rank = Math.ceil(0.9 * s.size).toInt() - 1
                return s[rank.coerceIn(0, s.size - 1)]
            }

            fun medianLong(list: List<Long>): Double {
                val s = list.sorted()
                return if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 else s[s.size / 2].toDouble()
            }

            fun p90Long(list: List<Long>): Long {
                val s = list.sorted()
                val rank = Math.ceil(0.9 * s.size).toInt() - 1
                return s[rank.coerceIn(0, s.size - 1)]
            }

            fun fmtMed(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

            val pathNames = listOf("KOTLIN_ORACLE", "RUST_SINGLE", "RUST_BATCH")
            for (path in 0..2) {
                val s = measurements[path]
                val elapsedSamples = s.map { it.elapsedMs }
                val threadSamples = s.map { it.threadCpuMs }
                val procSamples = s.map { it.processCpuMs }
                val allocSamples = s.map { it.javaAllocBytes }
                val nativeSamples = s.map { it.nativeDeltaBytes }

                val elapsedMed = medianDouble(elapsedSamples)
                val elapsedP90 = p90Double(elapsedSamples)
                val threadMed = medianDouble(threadSamples)
                val threadP90 = p90Double(threadSamples)
                val procMed = medianLong(procSamples)
                val allocMed = medianLong(allocSamples)
                val allocP90 = p90Long(allocSamples)
                val nativeMed = medianLong(nativeSamples)
                val jniCalls = if (path == 0) 0 else if (path == 1) size else 1

                println(
                    "TARGETED_BENCH nodes=$size path=${pathNames[path]} " +
                    "elapsed_med_ms=%.3f elapsed_p90_ms=%.3f ".format(elapsedMed, elapsedP90) +
                    "thread_cpu_med_ms=%.3f thread_cpu_p90_ms=%.3f proc_cpu_med_ms=%s ".format(threadMed, threadP90, fmtMed(procMed)) +
                    "alloc_med_bytes=%s alloc_p90_bytes=%d native_delta_med_bytes=%s jni_calls=%d ".format(fmtMed(allocMed), allocP90, fmtMed(nativeMed), jniCalls) +
                    "raw_elapsed_ms=[${elapsedSamples.joinToString(",") { "%.3f".format(it) }}] " +
                    "raw_alloc_bytes=[${allocSamples.joinToString(",")}]"
                )
            }
        }
    }

    @Test
    fun testSemanticEquivalenceAndEdgeCases() {
        // 1. Unicode and rich parameters test
        val richUri = "trojan://secret123@node.asia.example:8443?type=ws&path=%2Fstream&sni=edge.example&alpn=h2%2Chttp%2F1.1&allowInsecure=1#%E8%87%AA%E5%AE%9A%E4%B9%89%E8%8A%82%E7%82%B9_%E4%B8%9C%E4%BA%AC"
        val ktBean = io.nekohasekai.sagernet.oracle.parseTrojan(richUri).apply { initializeDefaultValues() }
        val rustSingle = RustProxyParser.parse(richUri)
        val rustBatch = RustProxyParser.parseBatch(listOf(richUri)).items[0].getOrThrow()

        for (candidate in listOf(rustSingle, rustBatch)) {
            assertEquals(ktBean.javaClass, candidate.javaClass)
            assertArrayEquals(KryoConverters.serialize(ktBean), KryoConverters.serialize(candidate))
            assertTrue(candidate is TrojanBean)
            val trojan = candidate as TrojanBean
            assertEquals(ktBean.name, trojan.name)
            assertEquals(ktBean.serverAddress, trojan.serverAddress)
            assertEquals(ktBean.serverPort, trojan.serverPort)
            assertEquals(ktBean.password, trojan.password)
            assertEquals(ktBean.type, trojan.type)
            assertEquals(ktBean.path, trojan.path)
            assertEquals(ktBean.security, trojan.security)
            assertEquals(ktBean.sni, trojan.sni)
            assertEquals(ktBean.alpn, trojan.alpn)
            assertEquals(ktBean.allowInsecure, trojan.allowInsecure)
        }

        // 2. Large input coverage: 2KB path
        val largePath = "/api/v1/" + "x".repeat(1500)
        val largeUri = "trojan://pass@large.example:443?type=ws&path=$largePath#LargeNode"
        val largeKt = io.nekohasekai.sagernet.oracle.parseTrojan(largeUri).apply { initializeDefaultValues() }
        val largeRust = RustProxyParser.parse(largeUri)
        val largeBatch = RustProxyParser.parseBatch(listOf(largeUri)).items[0].getOrThrow()
        assertEquals(largeKt.javaClass, largeRust.javaClass)
        assertEquals(largeKt.javaClass, largeBatch.javaClass)
        assertArrayEquals(KryoConverters.serialize(largeKt), KryoConverters.serialize(largeRust))
        assertArrayEquals(KryoConverters.serialize(largeKt), KryoConverters.serialize(largeBatch))
        assertEquals(largeKt.name, largeRust.name)
        assertEquals(largeKt.name, largeBatch.name)
        assertEquals(largeKt.path, (largeRust as TrojanBean).path)
        assertEquals(largeKt.path, (largeBatch as TrojanBean).path)

        // 3. Malformed input handling: all paths must reject
        val badUris = listOf(
            "trojan://",
            "not_a_protocol://pass@host:443",
            "trojan://pass@host:99999"
        )
        for (bad in badUris) {
            val ktFail = runCatching { io.nekohasekai.sagernet.oracle.parseTrojan(bad) }.isFailure
            val rustSingleFail = runCatching { RustProxyParser.parse(bad) }.isFailure
            val rustBatchFail = RustProxyParser.parseBatch(listOf(bad)).items[0].isFailure
            assertTrue("Kotlin Oracle must reject $bad", ktFail)
            assertTrue("Rust single must reject $bad", rustSingleFail)
            assertTrue("Rust batch must reject $bad", rustBatchFail)
        }
    }
}
