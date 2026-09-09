package io.nekohasekai.sagernet.ui.state

import org.junit.Assert.*
import org.junit.Test

class PanelUrlTest {
    @Test fun acceptsHttpPanelsIncludingLocalhostAndIpv6() {
        listOf("http://127.0.0.1:9090/ui/", "https://example.org/?token=secret", "http://[::1]:9090/", "HTTPS://example.org").forEach {
            assertTrue(it, PanelUrl.isValid(it))
        }
    }
    @Test fun rejectsMalformedAndNonWebUrls() {
        listOf("", "example.org", "https://", "file:///tmp/index.html", "javascript:alert(1)", "https://exa mple.org", "http://host:99999/").forEach {
            assertFalse(it, PanelUrl.isValid(it))
        }
    }
    @Test fun navigationIdentityAllowsBrowserCanonicalizationWithoutMixingRequests() {
        assertTrue(PanelUrl.sameTarget("HTTP://EXAMPLE.ORG:80", "http://example.org/"))
        assertFalse(PanelUrl.sameTarget("https://example.org/?token=first", "https://example.org/?token=second"))
        assertFalse(PanelUrl.sameTarget("https://example.org/one", "https://example.org/two"))
    }

}
