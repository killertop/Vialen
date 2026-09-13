package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.Subnet
import io.nekohasekai.sagernet.utils.addressLengthMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SubnetRobustnessTest {
    @Test
    fun addressFamilyMismatchIsRejectedWithoutThrowing() {
        assertFalse(addressLengthMatches(actual = 4, required = 16))
        assertFalse(addressLengthMatches(actual = 16, required = 4))
        assertTrue(addressLengthMatches(actual = 4, required = -1))
    }

    @Test
    fun numericFormattingRemainsStable() {
        val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
        val ipv6 = InetAddress.getByAddress(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()) + ByteArray(11) + byteArrayOf(1))
        assertEquals("192.0.2.1/24", Subnet(ipv4, 24).toString())
        assertEquals("2001:db8:0:0:0:0:0:1/64", Subnet(ipv6, 64).toString())
    }
}
