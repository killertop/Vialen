package io.nekohasekai.sagernet

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.RustProxyParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionBatchBenchmarkNativeTest {
    @Test fun measureActualBeanImportOnAndroid() {
        data class Sample(val ms: Double, val javaBytes: Long, val nativeDelta: Long)
        fun allocated() = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L
        for (size in listOf(100, 1000, 5000)) {
            val uris = List(size) { "trojan://pass$it@node$it.example:443?type=ws&path=%2Fp&sni=s.example#Node$it" }
            val measurements = List(3) { mutableListOf<Sample>() }
            fun run(path: Int): List<AbstractBean> = when (path) {
                0 -> uris.map { io.nekohasekai.sagernet.oracle.parseTrojan(it).apply { initializeDefaultValues() } }
                1 -> uris.map(RustProxyParser::parse)
                else -> RustProxyParser.parseBatch(uris).also {
                    assertEquals(1, it.batchCalls); assertEquals(0, it.singleCalls)
                }.items.map { it.getOrThrow() }
            }
            repeat(3) { for (path in 0..2) assertEquals(size, run(path).size) }
            repeat(9) { round ->
                for (offset in 0..2) {
                    val path = (round + offset) % 3
                    val beforeBytes = allocated()
                    val beforeNative = Debug.getNativeHeapAllocatedSize()
                    val start = System.nanoTime()
                    val beans = run(path)
                    val elapsed = (System.nanoTime() - start) / 1_000_000.0
                    val afterBytes = allocated()
                    measurements[path].add(Sample(elapsed,
                        if (beforeBytes < 0 || afterBytes < 0) -1 else afterBytes - beforeBytes,
                        Debug.getNativeHeapAllocatedSize() - beforeNative))
                    assertEquals(size, beans.size)
                    assertEquals("Node0", beans.first().name)
                    assertEquals("Node${size - 1}", beans.last().name)
                }
            }
            for (path in 0..2) {
                val s = measurements[path]
                println("PRODUCTION_BATCH_BENCH nodes=$size path=${listOf("KOTLIN_ORACLE", "RUST_SINGLE", "RUST_BATCH")[path]} " +
                    "median_ms=${s.map { it.ms }.sorted()[4]} java_allocated_bytes=${s.map { it.javaBytes }.sorted()[4]} " +
                    "native_end_delta_bytes=${s.map { it.nativeDelta }.sorted()[4]} jni_calls=${if (path == 0) 0 else if (path == 1) size else 1}")
            }
        }
    }
}
