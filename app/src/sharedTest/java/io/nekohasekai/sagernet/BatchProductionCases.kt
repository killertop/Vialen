package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.RustProxyParser
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

abstract class BatchProductionCases {
    @Test fun mixedProductionImportMatchesFrozenDispatchAndRetainsOrder() = runBlocking {
        val lines = listOf(
            "trojan://password@node.example:443?type=ws&path=%2Fa#Trojan",
            "tuic://user:password@node.example:443#TUIC",
            "hysteria://node.example:443?auth=password#HY1",
            "hy2://password@node.example:443#HY2",
            "vless://user@node.example:443?security=tls#VLESS",
            "vmess://user@node.example:443?type=ws#VMess",
            "ss://aes-128-gcm:password@node.example:8388#SS",
            "socks5://user:password@node.example:1080#SOCKS",
            "http://user:password@node.example:8080#HTTP",
            "anytls://password@node.example:443#AnyTLS",
            "trojan://invalid:bad", "unknown://skip", "", "#comment"
        )
        for (text in listOf(lines.joinToString("\n"), lines.joinToString(" "),
            "trojan://pw@a.example:443#name with spaces\nsocks5://b.example:1080#two")) {
            val expected = io.nekohasekai.sagernet.oracle.parseProxies(text)
            val actual = parseProxies(text)
            assertEquals(expected, actual)
            assertEquals(expected.map { it.displayName() }, actual.map { it.displayName() })
        }
    }

    @Test fun batchUsesOneNativeCallAndIsolatesFailures() {
        val links = (0 until 5000).map { "trojan://pw@node$it.example:443#节点|:$it" }.toMutableList()
        links[123] = "trojan://bad:bad"
        val result = RustProxyParser.parseBatch(links)
        assertEquals(1, result.batchCalls)
        assertEquals(0, result.singleCalls)
        assertEquals(5000, result.items.size)
        assertTrue(result.items[123].isFailure)
        result.items.forEachIndexed { i, node ->
            if (i != 123) assertEquals("节点|:$i", node.getOrThrow().name)
        }
    }

    @Test fun malformedUtf16CannotCorruptOtherBatchItems() {
        val inputs = listOf("trojan://pw@a.example:443#first", "trojan://pw@b.example:443#\uD800", "trojan://pw@c.example:443#last")
        val result = RustProxyParser.parseBatch(inputs)
        assertEquals(1, result.batchCalls)
        assertEquals("first", result.items[0].getOrThrow().name)
        assertTrue(result.items[1].isFailure)
        assertEquals("last", result.items[2].getOrThrow().name)
    }

    @Test fun largeImportChunksWithoutDroppingItemsOrSharingBeans() {
        val link = "socks5://node.example:1080#duplicate"
        val result = RustProxyParser.parseBatch(List(10001) { link })
        assertEquals(2, result.batchCalls)
        assertEquals(0, result.singleCalls)
        assertEquals(10001, result.items.count { it.isSuccess })
        assertNotSame(result.items.first().getOrThrow(), result.items.last().getOrThrow())
        result.items.first().getOrThrow().name = "changed"
        assertEquals("duplicate", result.items.last().getOrThrow().name)
        assertTrue(RustProxyParser.parseBatch(emptyList()).items.isEmpty())
    }
}
