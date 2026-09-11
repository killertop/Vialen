package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.SubscriptionNames
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

abstract class SubscriptionNamesCases {
    private fun reference(names: List<String>): List<String> {
        val used = HashSet<String>()
        return names.map { original ->
            var name = original; var index = 0
            while (name in used) { name = name.replace(" ($index)", "") + " (${++index})" }
            used.add(name); name
        }
    }
    @Test fun adaptiveNamesPreserveLegacyReplacementOrder() {
        val alphabet = listOf("", "A", "A (0)", "A (1)", "A (2)", " (0)A (1)", "A (1) (1)", "节点😀", "\uD800", "\uD801")
        val random = Random(742)
        val cases = listOf(emptyList(), List(1000) { "A" }, alphabet + alphabet + alphabet) +
            List(100) { List(random.nextInt(1, 120)) { alphabet.random(random) } }
        for (input in cases) {
            val expected = reference(input)
            assertEquals(expected, SubscriptionNames.unique(input))
        }
    }
}
