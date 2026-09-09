package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.Serializable
import org.junit.Assert.*
import org.junit.Test

/** Fixed bytes written by baseline c9696b1 and Kryo 5.2.1, never by the tested writer. */
class HistoricalKryoPayloadTest {
    private fun <T : Serializable> read(name: String, decode: (ByteArray) -> T): T {
        val bytes = javaClass.getResourceAsStream("/kryo-5.2.1/$name.bin")!!.use { it.readBytes() }
        return decode(bytes).also {
            assertArrayEquals("$name must retain the historical binary encoding", bytes, KryoConverters.serialize(it))
            if (it is AbstractBean) {
                assertEquals("旧版 fixture 🦀", it.name)
                assertEquals("{\"fixture\":true}", it.customOutboundJson)
                assertEquals("{\"note\":\"历史数据\"}", it.customConfigJson)
            }
        }
    }

    @Test fun socks() {
        val b = read("socks", KryoConverters::socksDeserialize)
        assertEquals("2001:db8::1", b.serverAddress)
        assertEquals(65535, b.serverPort.toInt())
        assertEquals(2, b.protocol.toInt())
        assertTrue(b.sUoT)
        assertEquals("user\u0000用户", b.username)
        assertEquals("pass🦀", b.password)
    }

    @Test fun vless() {
        val b = read("vless", KryoConverters::vmessDeserialize)
        assertEquals(-1, b.alterId.toInt())
        assertEquals("a3424107-160a-4286-9051-7d1c5a93b482", b.uuid)
        assertEquals("xtls-rprx-vision", b.encryption)
        assertEquals("ws", b.type)
        assertEquals("/路径?ed=2048", b.path)
        assertEquals("host.invalid", b.host)
        assertEquals("tls", b.security)
        assertEquals("sni.invalid", b.sni)
        assertEquals("h2\nhttp/1.1", b.alpn)
        assertEquals(2048, b.wsMaxEarlyData.toInt())
        assertEquals("Sec-WebSocket-Protocol", b.earlyDataHeaderName)
    }

    @Test fun trojan() {
        val b = read("trojan", KryoConverters::trojanDeserialize)
        assertEquals("fixture-password", b.password)
        assertEquals("grpc", b.type)
        assertEquals("fixture-service", b.path)
        assertEquals("tls", b.security)
        assertEquals("tls.invalid", b.sni)
    }

    @Test fun wireguard() {
        val b = read("wireguard", KryoConverters::wireguardDeserialize)
        assertEquals("10.0.0.2/32\nfd00::2/128", b.localAddress)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", b.privateKey)
        assertEquals("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", b.peerPublicKey)
        assertEquals("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=", b.peerPreSharedKey)
        assertEquals(1280, b.mtu.toInt())
        assertEquals("1,2,255", b.reserved)
    }

    @Test fun chain() {
        val b = read("chain", KryoConverters::chainDeserialize)
        assertEquals(listOf(1L, 4294967296L, Long.MAX_VALUE), b.proxies)
    }

    @Test fun subscription() {
        val b = read("subscription", KryoConverters::subscriptionDeserialize)
        assertEquals("https://subscription.invalid/配置?key=fixture", b.link)
        assertTrue(b.forceResolve)
        assertTrue(b.deduplication)
        assertTrue(b.updateWhenConnectedOnly)
        assertTrue(b.autoUpdate)
        assertEquals("Vialen-fixture/旧版", b.customUserAgent)
        assertEquals(17, b.autoUpdateDelay.toInt())
        assertEquals(1700000000L, b.lastUpdated.toLong())
        assertEquals("upload=1; download=2; total=999; expire=2000000000", b.subscriptionUserinfo)
    }
}
