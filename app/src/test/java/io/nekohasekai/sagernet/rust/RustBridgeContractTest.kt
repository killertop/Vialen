package io.nekohasekai.sagernet.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RustBridgeContractTest {
    @Test
    fun decodesSuccessWithoutLoadingNativeLibrary() {
        val result = RustBridge.decodeResponse("SUCCESS|1|3|e71fa2190541574b".toByteArray())
        assertEquals(RustProbeStatus.SUCCESS, result.status)
        assertEquals(1, result.contractVersion)
        assertEquals(3, result.inputLength)
        assertEquals("e71fa2190541574b", result.checksumHex)
    }

    @Test
    fun decodesInvalidInputError() {
        val result = RustBridge.decodeResponse("INVALID_INPUT|1|1048577|-".toByteArray())
        assertEquals(RustProbeStatus.INVALID_INPUT, result.status)
        assertEquals(1048577, result.inputLength)
        assertNull(result.checksumHex)
    }

    @Test
    fun malformedNativeResponseMapsToInternalError() {
        val result = RustBridge.decodeResponse(byteArrayOf(0xff.toByte(), 0x00))
        assertEquals(RustProbeStatus.INTERNAL_ERROR, result.status)
        assertEquals(0, result.inputLength)
        assertNull(result.checksumHex)
    }
}
