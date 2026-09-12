package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreProxyParserTest {
    @Test fun supportedSchemesMatchModernProfileImports() {
        listOf("ss", "socks", "socks4", "socks4a", "socks5", "http", "https",
            "vmess", "vless", "trojan", "hysteria2", "hy2", "tuic", "anytls").forEach {
            assertTrue(it, CoreProxyParser.supports("$it://example.com"))
            assertTrue(it, CoreProxyParser.supports("${it.uppercase()}://example.com"))
        }
    }

    @Test fun unrelatedAndRetiredEncodingsAreNotUriForms() {
        listOf("sn://vmess?data", "hysteria://example.com", "shadowtls://example.com",
            "wireguard://example.com", "example.com:443", "{\"profiles\":[]}").forEach {
            assertFalse(it, CoreProxyParser.supports(it))
        }
    }
}
