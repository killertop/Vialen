package io.nekohasekai.sagernet.core

import org.junit.Assert.*
import org.junit.Test

class CoreClientTest {
    @Test fun sameHostBoundaryImportsCompilesAndExportsTypedData() {
        val p = CoreClient.parseURI("socks5://user:pass@example.com:1080#node")
        assertEquals("5", p.socks!!.version)
        assertEquals("user", p.socks.username)
        assertEquals(p, CoreClient.parseURI(CoreClient.exportURI(p)))
        CoreClient.validate(p)
    }

    @Test fun nativeSharingPreservesFieldsThatStandardLinksCannotCarry() {
        val profile = CoreClient.parseURI("vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&type=ws")
            .let { it.copy(transport = it.transport!!.copy(headers = mapOf("x-test" to listOf("one", "two")))) }
        val result = CoreClient.importProfiles(CoreClient.exportProfiles(listOf(profile)))
        assertEquals(listOf(profile), result.requireComplete())
        assertThrows(IllegalStateException::class.java) { CoreClient.exportURI(profile) }
    }

    @Test fun partialOrEmptyImportsNeverPassPersistenceGate() {
        val result = CoreClient.importProfiles("socks5://example.com:1080\nunsupported://secret@example.com")
        assertEquals(1, result.profiles.size)
        assertThrows(IllegalArgumentException::class.java) { result.requireComplete() }
        assertThrows(IllegalArgumentException::class.java) { CoreClient.ImportResult().requireComplete() }
        val warning = result.copy(issues = listOf(CoreClient.ImportIssue(severity = "warning")))
        assertEquals(1, warning.requireComplete().size)
    }

    @Test fun malformedUtf16NeverBecomesReplacementCharacters() {
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { CoreClient.importProfiles("\uD800") }
    }
}
