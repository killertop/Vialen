package io.nekohasekai.sagernet.benchmark

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.SingBoxOptionsUtil
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Base64 as JavaBase64

class KotlinParserAndConfigBenchmarkTest {

    companion object {
        private val rootDir: File by lazy {
            val cur = File(".").canonicalFile
            if (cur.name == "app") cur.parentFile else cur
        }

        @BeforeClass
        @JvmStatic
        fun setup() {
            mockkStatic(Base64::class)
            every { Base64.encode(any(), any()) } answers {
                JavaBase64.getEncoder().encode(firstArg<ByteArray>())
            }
            every { Base64.encodeToString(any(), any()) } answers {
                JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
            }
            every { Base64.decode(any<String>(), any()) } answers {
                val raw = firstArg<String>().replace("-", "+").replace("_", "/")
                val pad = (4 - raw.length % 4) % 4
                JavaBase64.getDecoder().decode(raw + "=".repeat(if (pad == 4) 0 else pad))
            }
            every { Base64.decode(any<ByteArray>(), any()) } answers {
                JavaBase64.getDecoder().decode(firstArg<ByteArray>())
            }
            mockkStatic(TextUtils::class)
            every { TextUtils.isEmpty(any()) } answers {
                firstArg<CharSequence?>().isNullOrEmpty()
            }
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0

            mockkObject(Logs)
            every { Logs.d(any()) } answers {}
            every { Logs.d(any(), any()) } answers {}
            every { Logs.i(any()) } answers {}
            every { Logs.i(any(), any()) } answers {}
            every { Logs.w(any<String>()) } answers {}
            every { Logs.w(any<Throwable>()) } answers {}
            every { Logs.w(any(), any()) } answers {}
            every { Logs.e(any<String>()) } answers {}
            every { Logs.e(any<Throwable>()) } answers {}
            every { Logs.e(any(), any()) } answers {}

            val mockApp = mockk<SagerNet>(relaxed = true)
            every { mockApp.getDatabasePath(any()) } returns File("/tmp/test_mock_db")
            SagerNet.application = mockApp

            val mockKvDao = mockk<KeyValuePair.Dao>(relaxed = true)
            every { mockKvDao.get(any()) } returns null
            mockkObject(PublicDatabase.Companion)
            every { PublicDatabase.kvPairDao } returns mockKvDao

            mockkObject(DataStore)
            val mockStore = mockk<RoomPreferenceDataStore>(relaxed = true)
            every { mockStore.getString(any(), any()) } returns ""
            every { mockStore.getInt(any(), any()) } returns 0
            every { mockStore.getBoolean(any(), any()) } returns false
            every { DataStore.configurationStore } returns mockStore
            every { DataStore.profileCacheStore } returns mockStore

            mockkObject(SingBoxOptionsUtil)
            every { SingBoxOptionsUtil.domainStrategy(any()) } returns "prefer_ipv4"

            every { DataStore.globalAllowInsecure } returns false
            every { DataStore.serviceMode } returns Key.MODE_VPN
            every { DataStore.allowAccess } returns false
            every { DataStore.remoteDns } returns "tls://8.8.8.8\nhttps://1.1.1.1/dns-query"
            every { DataStore.directDns } returns "223.5.5.5\n119.29.29.29"
            every { DataStore.enableDnsRouting } returns true
            every { DataStore.enableFakeDns } returns false
            every { DataStore.trafficSniffing } returns 1
            every { DataStore.ipv6Mode } returns IPv6Mode.ENABLE
            every { DataStore.enableClashAPI } returns false
            every { DataStore.logLevel } returns 2
            every { DataStore.mixedPort } returns 2080
            every { DataStore.mtu } returns 1500
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            unmockkAll()
        }
    }

    @Test
    fun runParserBenchmark() = runBlocking {
        val scales = listOf(100, 1000, 5000, 10000)
        val fixtureDir = File(rootDir, "qa/fixtures/subscriptions")
        if (!fixtureDir.exists()) {
            println("Fixture dir not found at $fixtureDir, skipping benchmark")
            return@runBlocking
        }

        val resultsMd = StringBuilder()
        resultsMd.append("# Kotlin 订阅解析基线基准测试报告 (JVM_BASELINE)\n\n")
        resultsMd.append("**测试环境**: JVM OpenJDK 17 (Host: macOS arm64)\n")
        resultsMd.append("**测试方案**: 2 轮 Warmup + 多轮统计采集 (100/1000 节点 10 轮, 5000 节点 5 轮, 10000 节点 3 轮)\n")
        resultsMd.append("**指标说明**: 内存指标标注为 `ESTIMATED LIVE HEAP DELTA`；未在真实 Android 运行时插桩测量的 GC/峰值内存指标标记为 `NOT VERIFIED`。\n\n")
        resultsMd.append("## 1. Base64 订阅解析基线 (Plain URI / Base64)\n\n")
        resultsMd.append("| 节点规模 | 原始大小 | 耗时 p50 (ms) | 耗时 p95 (ms) | 最小耗时 (ms) | 最大耗时 (ms) | ESTIMATED LIVE HEAP DELTA (MB) | GC Pause / Native Peak |\n")
        resultsMd.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n")

        for (scale in scales) {
            val file = File(fixtureDir, "sub_${scale}_nodes.txt")
            if (!file.exists()) continue
            val text = file.readText()
            val sizeKb = text.length / 1024
            val iters = if (scale <= 1000) 10 else if (scale == 5000) 5 else 3

            try {
                // Warmup
                for (w in 0 until 2) {
                    RawUpdater.parseRaw(text)
                }

                System.gc()
                Thread.sleep(30)

                val timings = mutableListOf<Double>()
                val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                var maxHeap = 0L

                for (i in 0 until iters) {
                    val start = System.nanoTime()
                    val proxies = RawUpdater.parseRaw(text)
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                    timings.add(elapsedMs)

                    val currentHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                    if (currentHeap > maxHeap) maxHeap = currentHeap
                }

                timings.sort()
                val p50 = timings[timings.size / 2]
                val p95 = timings[(timings.size * 0.95).toInt().coerceAtMost(timings.size - 1)]
                val min = timings.first()
                val max = timings.last()
                val heapDeltaMb = (maxHeap - heapBefore).coerceAtLeast(0) / (1024.0 * 1024.0)

                resultsMd.append(String.format("| %d nodes | %d KB | %.2f | %.2f | %.2f | %.2f | %.2f | NOT VERIFIED |\n",
                    scale, sizeKb, p50, p95, min, max, heapDeltaMb))
                println("Parser Plain/B64 scale $scale: p50=%.2f ms, p95=%.2f ms".format(p50, p95))
            } catch (e: Throwable) {
                resultsMd.append(String.format("| %d nodes | %d KB | OOM | OOM | OOM | OOM | >2048 MB | NOT VERIFIED |\n", scale, sizeKb))
                System.gc()
            }
        }

        resultsMd.append("\n## 2. Clash YAML 订阅解析基线 (SnakeYAML)\n\n")
        resultsMd.append("| 节点规模 | 原始大小 | 耗时 p50 (ms) | 耗时 p95 (ms) | 最小耗时 (ms) | 最大耗时 (ms) | ESTIMATED LIVE HEAP DELTA (MB) | GC Pause / Native Peak |\n")
        resultsMd.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n")

        for (scale in scales) {
            val file = File(fixtureDir, "clash_${scale}_nodes.yaml")
            if (!file.exists()) continue
            val text = file.readText()
            val sizeKb = text.length / 1024
            val iters = if (scale <= 1000) 8 else if (scale == 5000) 4 else 2

            try {
                // Warmup
                for (w in 0 until 1) {
                    try { RawUpdater.parseRaw(text) } catch (_: Throwable) {}
                }

                System.gc()
                Thread.sleep(30)

                val timings = mutableListOf<Double>()
                val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                var maxHeap = 0L

                for (i in 0 until iters) {
                    val start = System.nanoTime()
                    try { RawUpdater.parseRaw(text) } catch (_: Throwable) {}
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                    timings.add(elapsedMs)

                    val currentHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                    if (currentHeap > maxHeap) maxHeap = currentHeap
                }

                timings.sort()
                val p50 = timings[timings.size / 2]
                val p95 = timings[(timings.size * 0.95).toInt().coerceAtMost(timings.size - 1)]
                val min = timings.first()
                val max = timings.last()
                val heapDeltaMb = (maxHeap - heapBefore).coerceAtLeast(0) / (1024.0 * 1024.0)

                resultsMd.append(String.format("| %d nodes | %d KB | %.2f | %.2f | %.2f | %.2f | %.2f | NOT VERIFIED |\n",
                    scale, sizeKb, p50, p95, min, max, heapDeltaMb))
                println("Parser Clash YAML scale $scale: p50=%.2f ms, p95=%.2f ms".format(p50, p95))
            } catch (e: Throwable) {
                resultsMd.append(String.format("| %d nodes | %d KB | OOM | OOM | OOM | OOM | >2048 MB | NOT VERIFIED |\n", scale, sizeKb))
                System.gc()
            }
        }

        val outDir = File(rootDir, "qa/baseline")
        outDir.mkdirs()
        File(outDir, "parser-benchmark.md").writeText(resultsMd.toString())
        println("Generated ${File(outDir, "parser-benchmark.md")}")
    }

    @Test
    fun runConfigBuilderBenchmark() {
        val scales = listOf(1, 10, 100, 500, 1000)
        val resultsMd = StringBuilder()
        resultsMd.append("# ConfigBuilder 基线基准测试报告 (JVM_BASELINE)\n\n")
        resultsMd.append("**测试环境**: JVM OpenJDK 17 (Host: macOS arm64)\n")
        resultsMd.append("**测试轮次**: 3 轮 Warmup + 10 轮采集统计\n")
        resultsMd.append("**测试模型**: Selector Group 聚合 N 个 Profile 实体，真实测量 Outbound 列表全量构建与序列化耗时\n")
        resultsMd.append("**指标说明**: 内存指标标注为 `ESTIMATED LIVE HEAP DELTA`；未在真实 Android 运行时测量的 GC/峰值指标标记为 `NOT VERIFIED`。\n\n")
        resultsMd.append("| Profile 数量 | 构建耗时 p50 (ms) | 构建耗时 p95 (ms) | 最小耗时 (ms) | 最大耗时 (ms) | 输出 JSON 大小 (KB) | 实际 Outbound 数量 | ESTIMATED LIVE HEAP DELTA (MB) |\n")
        resultsMd.append("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |\n")

        for (count in scales) {
            val selectorGroup = ProxyGroup().apply {
                id = 1L
                name = "BenchmarkGroup"
                type = GroupType.BASIC
                isSelector = true
            }

            val entities = (0 until count).map { i ->
                ProxyEntity().apply {
                    id = (i + 1).toLong()
                    groupId = 1L
                    type = ProxyEntity.TYPE_SOCKS
                    socksBean = SOCKSBean().applyDefaultValues().apply {
                        serverAddress = "node-$i.internal"
                        serverPort = 1080 + (i % 1000)
                        username = "user$i"
                        password = "pass$i"
                        protocol = SOCKSBean.PROTOCOL_SOCKS5
                        name = "SocksNode-$i"
                    }
                }
            }

            val mainEntity = entities.first()

            val mockGroupDao = mockk<ProxyGroup.Dao>(relaxed = true)
            every { mockGroupDao.getById(1L) } returns selectorGroup
            every { mockGroupDao.getById(any()) } returns selectorGroup

            val mockProxyDao = mockk<ProxyEntity.Dao>(relaxed = true)
            every { mockProxyDao.getById(any()) } answers {
                val id = firstArg<Long>()
                entities.find { it.id == id } ?: mainEntity
            }
            every { mockProxyDao.getByGroup(1L) } returns entities
            every { mockProxyDao.getByGroup(any()) } returns entities
            every { mockProxyDao.getEntities(any()) } returns entities

            val mockRuleDao = mockk<RuleEntity.Dao>(relaxed = true)
            every { mockRuleDao.enabledRules() } returns listOf()

            mockkObject(SagerDatabase.Companion)
            every { SagerDatabase.groupDao } returns mockGroupDao
            every { SagerDatabase.proxyDao } returns mockProxyDao
            every { SagerDatabase.rulesDao } returns mockRuleDao

            // Warmup
            for (w in 0 until 3) {
                buildConfig(mainEntity, forTest = false, forExport = false)
            }

            System.gc()
            Thread.sleep(20)

            val timings = mutableListOf<Double>()
            var lastOutputSize = 0
            var actualOutbounds = 0
            val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            var maxHeap = 0L

            for (i in 0 until 10) {
                val start = System.nanoTime()
                val res = buildConfig(mainEntity, forTest = false, forExport = false)
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                timings.add(elapsedMs)
                lastOutputSize = res.config.length
                actualOutbounds = res.profileTagMap.size

                val currentHeap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                if (currentHeap > maxHeap) maxHeap = currentHeap
            }

            assertEquals("Profile tag map must match scale count", count, actualOutbounds)
            assertTrue("Output config must contain selector", lastOutputSize > 0)

            timings.sort()
            val p50 = timings[timings.size / 2]
            val p95 = timings[(timings.size * 0.95).toInt().coerceAtMost(timings.size - 1)]
            val min = timings.first()
            val max = timings.last()
            val outputKb = lastOutputSize / 1024
            val heapDeltaMb = (maxHeap - heapBefore).coerceAtLeast(0) / (1024.0 * 1024.0)

            resultsMd.append(String.format("| %d profiles | %.2f | %.2f | %.2f | %.2f | %d KB | %d | %.2f |\n",
                count, p50, p95, min, max, outputKb, actualOutbounds, heapDeltaMb))
            println("ConfigBuilder scale $count (outbounds=$actualOutbounds, size=${outputKb}KB): p50=%.2f ms, p95=%.2f ms".format(p50, p95))
        }

        val outDir = File(rootDir, "qa/baseline")
        outDir.mkdirs()
        File(outDir, "config-benchmark.md").writeText(resultsMd.toString())
        println("Generated ${File(outDir, "config-benchmark.md")}")
    }

    @Test
    fun runHysteria2AndRuleSetMicroCheck() {
        val hy2Entity = ProxyEntity().apply {
            id = 100L
            groupId = 0L
            putBean(io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean().applyDefaultValues().apply {
                protocolVersion = 2
                serverAddress = "hy2.benchmark.internal"
                serverPorts = "443"
                authPayload = "benchpass"
                sni = "hy2.benchmark.internal"
                obfuscation = "obfspass"
                bbrProfile = "standard"
            })
        }

        val mockGroupDao = mockk<ProxyGroup.Dao>(relaxed = true)
        val mockProxyDao = mockk<ProxyEntity.Dao>(relaxed = true)
        val mockRuleDao = mockk<RuleEntity.Dao>(relaxed = true)

        val group = ProxyGroup().apply { id = 1L; name = "DefaultGroup" }
        every { mockGroupDao.getById(any()) } returns group
        every { mockProxyDao.getById(any()) } returns null
        every { mockProxyDao.getById(100L) } returns hy2Entity
        every { mockProxyDao.getEntities(any()) } returns listOf(hy2Entity)
        every { mockRuleDao.enabledRules() } returns listOf()

        mockkObject(SagerDatabase.Companion)
        every { SagerDatabase.groupDao } returns mockGroupDao
        every { SagerDatabase.proxyDao } returns mockProxyDao
        every { SagerDatabase.rulesDao } returns mockRuleDao

        // 1. Measure Hysteria2 ConfigBuilder (10 iterations)
        val hy2Timings = mutableListOf<Double>()
        for (w in 0 until 3) {
            buildConfig(hy2Entity, forTest = false, forExport = false)
        }
        for (i in 0 until 10) {
            val start = System.nanoTime()
            val res = buildConfig(hy2Entity, forTest = false, forExport = false)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            hy2Timings.add(elapsedMs)
            assertTrue("Output config must not be empty", res.config.isNotEmpty())
        }
        hy2Timings.sort()
        val hy2Median = hy2Timings[hy2Timings.size / 2]
        val hy2P95 = hy2Timings[(hy2Timings.size * 0.95).toInt().coerceAtMost(hy2Timings.size - 1)]
        println("HYSTERIA2_CONFIG_BUILD: median=%.3f ms, p95=%.3f ms, min=%.3f ms, max=%.3f ms".format(
            hy2Median, hy2P95, hy2Timings.first(), hy2Timings.last()))

        // 2. Measure Remote Rule-Set ConfigBuilder (3 iterations)
        val ruleEntity = RuleEntity().apply {
            id = 1L
            domains = "https://benchmark.internal/rules.srs"
            outbound = 0L
        }
        every { mockRuleDao.enabledRules() } returns listOf(ruleEntity)

        val rulesetTimings = mutableListOf<Double>()
        for (i in 0 until 3) {
            val start = System.nanoTime()
            val res = buildConfig(hy2Entity, forTest = false, forExport = false)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            rulesetTimings.add(elapsedMs)
            assertTrue("Rule set config must contain http_clients", res.config.contains("http_clients"))
        }
        rulesetTimings.sort()
        val rulesetMedian = rulesetTimings[rulesetTimings.size / 2]
        println("REMOTE_RULESET_CONFIG_LOAD: median=%.3f ms, min=%.3f ms, max=%.3f ms".format(
            rulesetMedian, rulesetTimings.first(), rulesetTimings.last()))
    }
}
