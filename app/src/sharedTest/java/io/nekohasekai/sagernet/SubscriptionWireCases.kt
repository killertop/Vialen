package io.nekohasekai.sagernet

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.group.SubscriptionWire
import io.nekohasekai.sagernet.rust.RustBridge
import io.nekohasekai.sagernet.rust.RustNative
import org.junit.Assert.*
import org.junit.Test

abstract class SubscriptionWireCases {
    @Test fun utf8ValidationMatchesStrictDecoder() {
        fun packet(payload: ByteArray) = byteArrayOf(86, 67, 87, 49, 4,
            payload.size.toByte(), 0, 0, 0) + payload
        val invalid = listOf(listOf(0xC0,0x80), listOf(0xED,0xA0,0x80), listOf(0xE0,0x80,0x80),
            listOf(0xF0,0x80,0x80,0x80), listOf(0xF4,0x90,0x80,0x80), listOf(0xF5,0x80,0x80,0x80),
            listOf(0xE2,0x82), listOf(0xC2,0x20), listOf(0x80))
        invalid.forEach { value -> assertTrue(runCatching { SubscriptionWire.decode(packet(value.map { it.toByte() }.toByteArray())) }.isFailure) }
        for (text in listOf("", "ascii\u0000", "é", "节点", "😀", "\uD7FF\uE000")) {
            assertEquals(text, SubscriptionWire.decode(packet(text.toByteArray())).asString)
        }
    }

    @Test fun rawWirePreservesProtocolMatrix() {
        val fixtures = listOf("", "sn://unknown:AA==", "sn://subscription?url=example",
            "https://example.com/path", "[Interface]\nPrivateKey = key\n[Peer]\nPublicKey = peer\nEndpoint = example.com:443") +
            listOf("socks5", "http", "ss", "vmess", "vless", "trojan", "anytls", "hysteria", "hysteria2", "tuic", "wireguard").map {
                "proxies: [{type: $it, name: '节点 😀', server: example.com, port: 443, password: p, uuid: id, cipher: aes-128-gcm, alpn: [h2, h3]}]"
            }
        fixtures.forEach { text ->
            val input = JsonObject().apply {
                addProperty("version", 1); addProperty("mode", "raw")
                addProperty("text", text); addProperty("file_name", "")
            }.toString().toByteArray()
            val actual = checkNotNull(RustNative.nativeParseRawSubscriptionOptimized(input))
            val decoded = if (actual.take(4) == listOf<Byte>(86,67,87,49)) SubscriptionWire.decode(actual)
                else JsonParser.parseString(actual.decodeToString())
            assertEquals(text, JsonParser.parseString(RustBridge.parseRawSubscription(input).decodeToString()), decoded)
        }
    }

    @Test fun malformedWireFailsClosed() {
        for (bytes in listOf(byteArrayOf(), "BAD!".toByteArray(), "VCW1".toByteArray(),
            "VCW1\u0000x".toByteArray(), "VCW1\u0004\u007f\u0000\u0000\u0000".toByteArray(),
            "VCW1\u007f".toByteArray(), byteArrayOf(86,67,87,49,4,1,0,0,0,-1),
            byteArrayOf(86,67,87,49,3,1,0,0,0,120))) {
            assertTrue(runCatching { SubscriptionWire.decode(bytes) }.isFailure)
        }
    }
}
