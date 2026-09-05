package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.rust.RustBridge
import io.nekohasekai.sagernet.rust.RustProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RustBridgeNativeTest {
    @Test
    fun subscriptionBeanDedupUsesRealRustLibrary() {
        assertEquals(listOf(0, 1, 0), RustBridge.rankDedupKeys(listOf("\uD800", "\uD801", "\uD800")))
        assertEquals(List(10001) { 0 }, RustBridge.rankDedupKeys(List(10001) { "" }))
        fun bean(name: String, host: String, port: Int) =
            io.nekohasekai.sagernet.fmt.trojan.TrojanBean().apply {
                initializeDefaultValues()
                this.name = name
                serverAddress = host
                serverPort = port
                customOutboundJson = "local"
            }
        val first = bean("节点 🔥", "node1", 23)
        val second = bean("other", "node12", 3)
        val result = io.nekohasekai.sagernet.group.SubscriptionDedup.apply(
            listOf(first, second, bean("duplicate", "node1", 23))
        )
        assertEquals(2, result.proxies.size)
        org.junit.Assert.assertSame(first, result.proxies[0])
        org.junit.Assert.assertSame(second, result.proxies[1])
        assertEquals("local", result.proxies[0].customOutboundJson)
        assertEquals(listOf("节点 🔥 (0)", "duplicate (0)"), result.duplicates)
    }

    @Test
    fun byteArrayRoundTripUsesRealRustLibrary() {
        val input = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val result = RustBridge.probe(input)
        assertEquals(RustProbeStatus.SUCCESS, result.status)
        assertEquals(input.size, result.inputLength)
        assertNotNull(result.checksumHex)
    }

    @Test
    fun emptyInputUsesRealRustLibrary() {
        val result = RustBridge.probe(byteArrayOf())
        assertEquals(RustProbeStatus.SUCCESS, result.status)
        assertEquals(0, result.inputLength)
        assertEquals("cbf29ce484222325", result.checksumHex)
    }

    @Test
    fun batchParserUsesRealRustLibrary() {
        val uris = listOf(
            "ss://chacha20-ietf-poly1305:pass1@192.168.1.1:8388#Node1",
            "socks5://user:pass@10.0.0.1:1080#SocksNode",
            "invalid://scheme:123",
        )
        val results = RustBridge.parseProxyBatch(uris)
        assertEquals(3, results.size)
        assertEquals("SUCCESS", results[0].status)
        assertEquals("shadowsocks", results[0].protocol)
        assertEquals("SUCCESS", results[1].status)
        assertEquals("socks5", results[1].protocol)
        assertEquals("INVALID_SCHEME", results[2].status)
    }

    @Test
    fun diffSubscriptionUsesRealRustLibrary() {
        val oldUris = listOf(
            "ss://chacha20-ietf-poly1305:p1@1.1.1.1:8388#Node1",
            "ss://chacha20-ietf-poly1305:oldpwd@1.1.1.2:8388#Node2",
        )
        val newUris = listOf(
            "ss://chacha20-ietf-poly1305:p1@1.1.1.1:8388#Node1",
            "ss://chacha20-ietf-poly1305:newpwd@1.1.1.2:8388#Node2",
            "ss://chacha20-ietf-poly1305:p3@1.1.1.3:8388#Node3",
        )
        val diff = RustBridge.diffSubscription(oldUris, newUris)
        assertEquals(1, diff.unchanged.size)
        assertEquals(1, diff.updated.size)
        assertEquals(1, diff.added.size)
        assertEquals(0, diff.removed.size)
    }

    @Test
    fun batchParserAndroidBenchmark() {
        val sizes = listOf(100, 1000, 5000)
        val iterations = 5

        println("\n========================================================")
        println("  ANDROID RUNTIME BENCHMARK: ART Single vs Batch JNI")
        println("========================================================")

        for (size in sizes) {
            val uris = ArrayList<String>(size)
            for (i in 0 until size) {
                uris.add("ss://chacha20-ietf-poly1305:pass_${i}@192.168.${(i / 256) % 256}.${i % 256}:${1024 + (i % 60000)}#Node_$i")
            }

            // Warmup
            RustBridge.parseProxyBatch(uris.take(50))

            // Single JNI
            val singleTimes = DoubleArray(iterations)
            for (it in 0 until iterations) {
                val start = System.nanoTime()
                val list = ArrayList<io.nekohasekai.sagernet.rust.CanonicalProxyResult>(size)
                for (u in uris) {
                    list.add(RustBridge.parseProxy(u))
                }
                singleTimes[it] = (System.nanoTime() - start) / 1_000_000.0
            }
            singleTimes.sort()

            // True Batch JNI
            val batchTimes = DoubleArray(iterations)
            for (it in 0 until iterations) {
                val start = System.nanoTime()
                val list = RustBridge.parseProxyBatch(uris)
                batchTimes[it] = (System.nanoTime() - start) / 1_000_000.0
            }
            batchTimes.sort()

            val singleMedian = singleTimes[iterations / 2]
            val batchMedian = batchTimes[iterations / 2]
            val ratio = singleMedian / batchMedian.coerceAtLeast(0.01)

            println("Android ART Size $size nodes: Single JNI median = ${"%.2f".format(singleMedian)} ms ($size calls), Batch JNI median = ${"%.2f".format(batchMedian)} ms (1 call), Ratio = ${"%.2f".format(ratio)}x")
        }
        println("========================================================\n")
    }
}
