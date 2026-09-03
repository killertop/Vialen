package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.rust.RustBridge
import io.nekohasekai.sagernet.rust.RustProbeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RustBridgeNativeTest {
    @Test
    fun byteArrayRoundTripUsesRealRustLibrary() {
        val input = byteArrayOf(0x00, 0x7f, 0xff.toByte())
        val result = RustBridge.probe(input)
        assertEquals(RustProbeStatus.SUCCESS, result.status)
        assertEquals(input.size, result.inputLength)
        assertNotNull(result.checksumHex)
    }

    @Test
    fun emptyInputUsesRealRustLibrary() {
        val result = RustBridge.probe(byteArrayOf())
        assertEquals(RustProbeStatus.SUCCESS, result.status)
        assertEquals(0, result.inputLength)
        assertEquals("cbf29ce484222325", result.checksumHex)
    }
}
