package io.nekohasekai.sagernet.ui.state

import org.junit.Assert.*
import org.junit.Test

class BindingGenerationTest {
    @Test fun repeatedIdentityAndRecycleRejectLateResults() {
        val gate = BindingGeneration()
        val firstA = gate.next()
        val b = gate.next()
        val secondA = gate.next()
        assertTrue(gate.accepts(secondA))
        assertFalse(gate.accepts(firstA))
        assertFalse(gate.accepts(b))
        val refreshedA = gate.next()
        assertFalse(gate.accepts(secondA))
        assertTrue(gate.accepts(refreshedA))
        gate.next() // RecyclerView recycling or explicit invalidation
        assertFalse(gate.accepts(refreshedA))
    }
}
