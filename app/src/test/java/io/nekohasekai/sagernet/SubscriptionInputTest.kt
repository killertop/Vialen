package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.SubscriptionLink
import io.nekohasekai.sagernet.utils.UserFacingError
import org.junit.Assert.*
import org.junit.Test

class SubscriptionInputTest {
    @Test fun acceptsWebLinksWithoutChangingTokensAndPreservesExistingDocuments() {
        val link = "https://example.com/sub?token=a%2Fb%2B&x=1"
        assertEquals(link, SubscriptionLink.normalize("  $link\n"))
        assertEquals("content://provider/id", SubscriptionLink.normalize("content://provider/id", "content://provider/id"))
    }

    @Test fun rejectsEmptyMalformedAndNonSubscriptionLinksBeforeSaving() {
        for (value in listOf("", "  ", "vless://user@example.com", "file:///etc/passwd",
            "content://provider/new", "https://", "https://user:password@example.com/",
            "https://example.com:99999", "https://example.com/a b")) {
            assertThrows(value, IllegalArgumentException::class.java) { SubscriptionLink.normalize(value) }
        }
    }

    @Test fun networkAndUnknownErrorsAreShortChineseWithoutPrivateUrls() {
        assertEquals("服务器暂不可用，请稍后重试", UserFacingError.describe("HTTP 503 https://example.com?token=secret"))
        assertEquals("请求超时，请重试", UserFacingError.describe(java.net.SocketTimeoutException()))
        assertEquals("域名解析失败，请检查网络", UserFacingError.describe("no such host example.com"))
        assertEquals("请填写订阅链接", UserFacingError.describe(IllegalArgumentException("请填写订阅链接")))
        val unknown = UserFacingError.describe("bad request to https://user:secret@example.com")
        assertFalse(unknown.contains("secret"))
        assertFalse(unknown.contains("example.com"))
        assertTrue(unknown.any { it in '\u4e00'..'\u9fff' })
    }
}
