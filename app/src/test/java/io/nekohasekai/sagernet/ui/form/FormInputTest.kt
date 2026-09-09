package io.nekohasekai.sagernet.ui.form

import org.junit.Assert.*
import org.junit.Test

class FormInputTest {
    @Test fun mtuRejectsMalformedOverflowAndBothRangeEdges() {
        listOf("", " ", "abc", "2147483648", "999", "10001", "0", "-1").forEach {
            assertNull(it, validatedFormInt(it, 1000..10000))
        }
        listOf(1000, 1500, 10000).forEach {
            assertEquals(it, validatedFormInt(it.toString(), 1000..10000))
        }
    }
    @Test fun logBufferNeverSilentlyDefaultsBadInput() {
        listOf("", "0", "-1", "999999999999999999999").forEach {
            assertNull(validatedFormInt(it, 1..Int.MAX_VALUE))
        }
        assertEquals(50, validatedFormInt("50", 1..Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, validatedFormInt("2147483647", 1..Int.MAX_VALUE))
    }
}
