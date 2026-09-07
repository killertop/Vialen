package io.nekohasekai.sagernet.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun build38FieldsPayload(vararg overrides: Pair<Int, String>): String {
        val fields = MutableList(38) { "" }
        for ((idx, value) in overrides) {
            fields[idx] = value
        }
        val sb = StringBuilder("SUCCESS\n")
        for (f in fields) {
            sb.append(f.length).append(':').append(f)
        }
        return sb.toString()
    }

    @Test
    fun decodesProxySuccessWithoutLoadingNativeLibrary() {
        val payload = build38FieldsPayload(
            0 to "shadowsocks",
            1 to "1.2.3.4",
            2 to "8388",
            3 to "aes-256-gcm",
            4 to "secret",
            5 to "obfs-local;obfs=http",
            6 to "TestNode",
            34 to "1",
            35 to "GunService",
            36 to "16",
            37 to "auto",
        )
        val result = RustBridge.decodeProxyResponse(payload)
        assertEquals("SUCCESS", result.status)
        assertEquals("shadowsocks", result.protocol)
        assertEquals("1.2.3.4", result.server)
        assertEquals(8388, result.port)
        assertEquals("aes-256-gcm", result.username)
        assertEquals("secret", result.password)
        assertEquals("obfs-local;obfs=http", result.plugin)
        assertEquals("TestNode", result.name)
        assertEquals(true, result.tlsEnabled)
        assertEquals("GunService", result.serviceName)
        assertEquals(16, result.alterId)
        assertEquals("auto", result.encryption)
    }

    @Test
    fun testRejectsProxySuccessWithInvalidFieldCount() {
        // Less than 38 fields (e.g. 7 fields)
        val shortPayload = "SUCCESS\n11:shadowsocks7:1.2.3.44:838811:aes-256-gcm6:secret20:obfs-local;obfs=http8:TestNode"
        val resultShort = RustBridge.decodeProxyResponse(shortPayload)
        assertEquals("INTERNAL_ERROR", resultShort.status)
        assertTrue(resultShort.error!!.contains("Expected exactly 38 fields"))

        // More than 38 fields (39 fields)
        val longPayload = "SUCCESS\n" + "0:".repeat(39)
        val resultLong = RustBridge.decodeProxyResponse(longPayload)
        assertEquals("INTERNAL_ERROR", resultLong.status)
        assertTrue(resultLong.error!!.contains("Expected exactly 38 fields"))
    }

    @Test
    fun testDelimiterSafetyWithPipes() {
        // password contains "|", name contains "|", plugin contains "|"
        val proto = "shadowsocks"
        val server = "node.net"
        val port = "8388"
        val user = "chacha20-ietf-poly1305"
        val pass = "pass|with|pipes|and:colons"
        val plugin = "obfs|opt=1;opt=2"
        val name = "Name|With|Pipes"
        val payload = build38FieldsPayload(
            0 to proto,
            1 to server,
            2 to port,
            3 to user,
            4 to pass,
            5 to plugin,
            6 to name,
        )

        val result = RustBridge.decodeProxyResponse(payload)
        assertEquals("SUCCESS", result.status)
        assertEquals("shadowsocks", result.protocol)
        assertEquals("node.net", result.server)
        assertEquals(8388, result.port)
        assertEquals(user, result.username)
        assertEquals(pass, result.password)
        assertEquals(plugin, result.plugin)
        assertEquals(name, result.name)
    }

    @Test
    fun decodesProxyErrorWithoutLoadingNativeLibrary() {
        val payload = "INVALID_PORT\n12:missing port"
        val result = RustBridge.decodeProxyResponse(payload)
        assertEquals("INVALID_PORT", result.status)
        assertEquals("missing port", result.error)
    }

    @Test
    fun decodesSubscriptionSuccessWithoutLoadingNativeLibrary() {
        val payload = "ss://node1\nss://node2\n"
        val result = RustBridge.decodeSubscriptionResponse(payload)
        assertEquals("SUCCESS", result.status)
        assertEquals(listOf("ss://node1", "ss://node2"), result.lines)
    }
}
