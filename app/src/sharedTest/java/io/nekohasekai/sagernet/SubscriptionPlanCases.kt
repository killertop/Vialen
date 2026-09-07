package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

abstract class SubscriptionPlanCases {
    private fun bean(kind: Int, name: String, host: String): AbstractBean {
        val result = when (kind % 6) {
            0 -> TrojanBean().apply { password = host; type = "ws"; path = "/$host" }
            1 -> TuicBean().apply { uuid = host; token = host }
            2 -> HysteriaBean().apply { authPayload = host; bbrProfile = host }
            3 -> VMessBean().apply { alterId = -1; uuid = host }
            4 -> ConfigBean().apply { config = "{raw:$host}" }
            else -> SOCKSBean().apply { username = host; password = host }
        }
        result.name = name; result.serverAddress = host; result.serverPort = 443
        result.initializeDefaultValues()
        return result
    }

    @Test fun classTaggedProjectionMatchesBeanEqualityWithRetainedOverrides() {
        for (oldKind in 0..5) for (newKind in 0..5) for (changed in listOf(false, true)) {
            val old = bean(oldKind, "old name", "host").apply { customOutboundJson = "retained"; customConfigJson = "config" }
            val new = bean(newKind, "new name", if (changed) "other" else "host")
            val projected = KryoConverters.subscriptionContent(old).contentEquals(KryoConverters.subscriptionContent(new))
            new.customOutboundJson = old.customOutboundJson; new.customConfigJson = old.customConfigJson
            assertEquals(old == new, projected)
            assertEquals("old name", old.name)
        }
    }

    @Test fun nativePlanMatchesProductionEqualityAndLastNameSelection() {
        val random = Random(42)
        repeat(100) {
            val old = List(20) { bean(random.nextInt(6), "N${random.nextInt(12)}", "host${random.nextInt(4)}") }
            val orders = LongArray(old.size) { random.nextLong(1, 40) }
            val new = (0..15).shuffled(random).take(random.nextInt(17)).map { name ->
                bean(random.nextInt(6), "N$name", "host${random.nextInt(4)}")
            }
            val actual = RustBridge.planSubscription(old.map { it.displayName() }, Array(old.size) { KryoConverters.subscriptionContent(old[it]) },
                orders, new.map { it.displayName() }, Array(new.size) { KryoConverters.subscriptionContent(new[it]) })
            val expectedIndices = new.map { node -> old.indexOfLast { it.displayName() == node.displayName() } }
            assertArrayEquals(expectedIndices.toIntArray(), actual.oldIndices)
            expectedIndices.forEachIndexed { index, previous ->
                val expectedFlags = if (previous == -1) 1 else
                    (if (old[previous] != new[index]) 1 else 0) or (if (orders[previous] != index + 1L) 2 else 0)
                assertEquals(expectedFlags, actual.flags[index])
            }
            assertArrayEquals(old.indices.filter { it !in expectedIndices }.toIntArray(), actual.removedIndices)
        }
    }

    @Test fun productionPlanPreservesUtf16AndAcceptsLargeSubscriptions() {
        val names = List(10001) { "节点$it" } + listOf("\uD800", "\uD801")
        val contents = Array(names.size) { byteArrayOf(0, -1, 1) }
        val plan = RustBridge.planSubscription(names, contents, LongArray(names.size) { it + 1L }, names, contents)
        assertArrayEquals(IntArray(names.size) { it }, plan.oldIndices)
        assertTrue(plan.flags.all { it == 0 })
        assertTrue(plan.removedIndices.isEmpty())
    }

    @Test fun malformedNativePlanCannotReachPersistence() {
        for (raw in listOf(intArrayOf(), intArrayOf(1,0), intArrayOf(1,0,-2,1),
            intArrayOf(1,0,0,4), intArrayOf(1,1,0,0,0), intArrayOf(1,0,-1,0))) {
            assertThrows(IllegalStateException::class.java) { RustBridge.decodePersistencePlan(raw, 1, 1) }
        }
        assertThrows(IllegalStateException::class.java) {
            RustBridge.planSubscription(emptyList(), emptyArray(), longArrayOf(), listOf("same", "same"), arrayOf(byteArrayOf(), byteArrayOf()))
        }
    }
}
