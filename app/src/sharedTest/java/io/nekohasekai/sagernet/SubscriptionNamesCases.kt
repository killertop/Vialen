package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.SubscriptionNames
import org.junit.Assert.*
import org.junit.Test

abstract class SubscriptionNamesCases {
    @Test fun presentationNamesRemainUniqueWithoutChangingDistinctNames() {
        val input = listOf("A", "A", "A (2)", "", "", "节点😀", "节点😀") + List(1000) { "same" }
        val output = SubscriptionNames.unique(input)
        assertEquals(input.size, output.size)
        assertEquals(output.size, output.toSet().size)
        assertEquals("A", output[0])
        assertEquals(output, SubscriptionNames.unique(input))
    }
}
