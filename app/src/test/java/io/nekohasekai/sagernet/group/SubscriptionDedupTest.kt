package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDedupTest {
    private fun node(name: String, host: String = "host", port: Int = 443) = TrojanBean().apply {
        initializeDefaultValues()
        this.name = name
        serverAddress = host
        serverPort = port
    }

    // Frozen production algorithm from RawUpdater before this cutover.
    private fun legacy(proxies: List<AbstractBean>): SubscriptionDedup.Result {
        val unique = LinkedHashSet<Protocols.Deduplication>()
        val names = HashMap<Protocols.Deduplication, String>()
        val duplicates = ArrayList<String>()
        for (bean in proxies) {
            val proxy = Protocols.Deduplication(bean, bean.javaClass.toString())
            if (!unique.add(proxy)) {
                val index = unique.indexOf(proxy)
                if (names.containsKey(proxy)) {
                    val name = names[proxy]!!.replace(" ($index)", "")
                    if (name.isNotBlank()) {
                        duplicates.add("$name ($index)")
                        names[proxy] = ""
                    }
                }
                duplicates.add(bean.displayName() + " ($index)")
            } else names[proxy] = bean.displayName()
        }
        unique.retainAll(names.keys)
        return SubscriptionDedup.Result(unique.map { it.bean }, duplicates)
    }

    @Test fun productionGroupingAndDuplicateReportingAgree() {
        val inputs = (0 until 60).map { i ->
            node("节点 🔥 $i", "host${i % 7}", 443 + i % 3)
        } + listOf(node("name (0)"), node("second"), node("third"))
        val expected = legacy(inputs)
        val actual = SubscriptionDedup.apply(inputs)
        assertEquals(expected.duplicates, actual.duplicates)
        assertEquals(expected.proxies.size, actual.proxies.size)
        actual.proxies.indices.forEach { assertSame(expected.proxies[it], actual.proxies[it]) }
    }

    @Test fun collisionCorrectionAndOriginalBeanPreservation() {
        val first = node("first", "node1", 23).apply { customOutboundJson = "local override" }
        val second = node("second", "node12", 3)
        assertEquals(1, legacy(listOf(first, second)).proxies.size)
        val result = SubscriptionDedup.apply(listOf(first, second, node("duplicate", "node1", 23)))
        assertEquals(2, result.proxies.size)
        assertSame(first, result.proxies[0])
        assertSame(second, result.proxies[1])
        assertEquals("local override", first.customOutboundJson)
    }

    @Test fun configAndProtocolFamiliesStaySeparate() {
        val config = ConfigBean().apply { initializeDefaultValues(); name = "config"; this.config = "{}" }
        val sameConfig = ConfigBean().apply { initializeDefaultValues(); name = "duplicate"; this.config = "{}" }
        val socks = SOCKSBean().apply { initializeDefaultValues(); name = "socks"; serverAddress = "host"; serverPort = 443 }
        val nodes = listOf(config, sameConfig, node("trojan"), socks)
        val result = SubscriptionDedup.apply(nodes)
        assertEquals(3, result.proxies.size)
        assertEquals(legacy(nodes).duplicates, result.duplicates)
        assertSame(config, result.proxies[0])
    }

    @Test fun emptyAndUnicodeKeysAndBounds() {
        assertTrue(SubscriptionDedup.apply(emptyList()).proxies.isEmpty())
        assertEquals(listOf(0, 1, 0, 2), RustBridge.rankDedupKeys(listOf("🔥:", "", "🔥:", "节点")))
        assertEquals(listOf(0, 1, 0), RustBridge.rankDedupKeys(listOf("\uD800", "\uD801", "\uD800")))
        assertEquals(List(10001) { 0 }, RustBridge.rankDedupKeys(List(10001) { "" }))
        assertEquals(listOf(0), RustBridge.rankDedupKeys(listOf("x".repeat(10 * 1024 * 1024 + 1))))
    }
}
