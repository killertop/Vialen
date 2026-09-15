package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.core.CoreClient
import org.junit.Assert.*
import org.junit.Test

class ClipboardSubscriptionTest {
    @Test fun webSubscriptionPreservesOpaqueQueryAndFlag() {
        val link = "https://example.test/sub?token=synthetic%2Bvalue%26x&sb&x=one&x=two"
        assertEquals(link, ClipboardSubscription.find("  $link\n"))
        assertEquals("http://example.test/feed", ClipboardSubscription.find("http://example.test/feed"))
    }

    @Test fun existingHttpProxyEndpointsRemainNodes() {
        for (link in listOf("https://user:pass@example.test:443", "http://example.test:8080", "https://example.test?sni=example.test")) {
            assertNull(ClipboardSubscription.find(link))
            assertEquals("http", CoreClient.parseURI(link).type)
        }
    }

    @Test fun nodeListsAndDocumentsNeverBecomeSubscriptionUrls() {
        for (value in listOf("socks://127.0.0.1:1080", "https://example.test/sub\nsocks://127.0.0.1:1080", "{\"outbounds\":[]}", "")) {
            assertNull(ClipboardSubscription.find(value))
        }
    }

    @Test fun invalidWebSubscriptionIsRejectedBeforeConfirmation() {
        for (link in listOf("https://example.test:65536/sub", "https://user:pass@example.test/sub", "https://example.test\\sub")) {
            assertThrows(IllegalArgumentException::class.java) { ClipboardSubscription.find(link) }
        }
    }
}
