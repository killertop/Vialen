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

    @Test
    fun decodesProxySuccessWithoutLoadingNativeLibrary() {
        // SUCCESS\n11:shadowsocks7:1.2.3.44:838811:aes-256-gcm6:secret20:obfs-local;obfs=http8:TestNode
        val payload = "SUCCESS\n11:shadowsocks7:1.2.3.44:838811:aes-256-gcm6:secret20:obfs-local;obfs=http8:TestNode"
        val result = RustBridge.decodeProxyResponse(payload)
        assertEquals("SUCCESS", result.status)
        assertEquals("shadowsocks", result.protocol)
        assertEquals("1.2.3.4", result.server)
        assertEquals(8388, result.port)
        assertEquals("aes-256-gcm", result.username)
        assertEquals("secret", result.password)
        assertEquals("obfs-local;obfs=http", result.plugin)
        assertEquals("TestNode", result.name)
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
        val payload = "SUCCESS\n" +
                "${proto.length}:$proto" +
                "${server.length}:$server" +
                "${port.length}:$port" +
                "${user.length}:$user" +
                "${pass.length}:$pass" +
                "${plugin.length}:$plugin" +
                "${name.length}:$name"

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
