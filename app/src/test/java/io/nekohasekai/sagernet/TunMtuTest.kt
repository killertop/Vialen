package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.TunMtu
import org.junit.Assert.*
import org.junit.Test

class TunMtuTest {
    @Test fun retainsDefaultAndAcceptsBoundaryValues() {
        assertEquals(9000, TunMtu.DEFAULT)
        listOf(1280, 1500, 9000, 10000, 65535).forEach { assertEquals(it, TunMtu.requireValid(it)) }
    }
    @Test fun rejectsLegacyInvalidValuesWithoutSubstitution() {
        listOf(-1, 0, 1000, 1279, 65536).forEach { value ->
            try { TunMtu.requireValid(value); fail("Accepted $value") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("1280")) }
        }
    }
}
