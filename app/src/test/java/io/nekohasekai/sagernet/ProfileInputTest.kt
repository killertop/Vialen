package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.ktx.readProfileBytes
import io.nekohasekai.sagernet.ktx.readProfileText
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ProfileInputTest {
    @Test fun budgetRejectsOneExtraByteAndPreservesExactLimit() {
        assertArrayEquals(byteArrayOf(1,2,3), ByteArrayInputStream(byteArrayOf(1,2,3)).readProfileBytes(3))
        assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream(byteArrayOf(1,2,3,4)).readProfileBytes(3) }
    }
    @Test fun malformedInputIsNotSilentlyReplaced() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)).readProfileText() }
    }
}
