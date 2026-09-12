package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.core.CoreClient
import io.nekohasekai.sagernet.core.Profile
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import kotlinx.coroutines.*

class TypedSubscriptionTest {
    private fun node(password: String = "one", name: String = "shared", id: String = "") =
        Profile(id = id, name = name, type = "trojan", server = "example.org", port = 443,
            tls = Profile.Tls(), trojan = Profile.Password(password))

    @Test fun dedupRetainsDistinctCredentialsTlsAndTransport() {
        val first = node()
        val entries = listOf(first, first.copy(id = "provider", name = "alias"), node("two"),
            first.copy(tls = Profile.Tls(serverName = "other.example")),
            first.copy(transport = Profile.Transport(type = "ws", path = "/path")))
        val result = SubscriptionDedup.apply(entries)
        assertEquals(listOf(entries[0], entries[2], entries[3], entries[4]), result.proxies)
        assertEquals(listOf("alias"), result.duplicates)
    }

    @Test fun duplicateNamesReserveUnchangedNodesBeforeChangedNodes() {
        val first = node().copy(id = "local-a")
        val second = node("two").copy(id = "local-b")
        val key = SubscriptionPersistence.sourceKey(node())
        val old = listOf(first, second).map { SubscriptionPersistence.Existing(key, it) }
        assertEquals(listOf(1, 0), SubscriptionPersistence.match(old, listOf(node("new"), node())))
        assertEquals(listOf(1, 0, null), SubscriptionPersistence.match(old, listOf(node("two"), node(), node("three"))))
    }

    @Test fun providerIdentitySurvivesRenameAndEndpointChange() {
        val original = node(id = "source-a")
        val old = listOf(SubscriptionPersistence.Existing(SubscriptionPersistence.sourceKey(original), original.copy(id = "local-a")))
        assertEquals(listOf(0), SubscriptionPersistence.match(old,
            listOf(original.copy(name = "renamed", server = "new.example"))))
        assertEquals(listOf(null), SubscriptionPersistence.match(old, listOf(original.copy(id = "source-b"))))
    }

    @Test fun namesAreOnlyReportLabelsAndDoNotEatExistingSuffixes() {
        assertEquals(listOf("A", "A (3)", "A (2)", "A (4)"),
            SubscriptionNames.unique(listOf("A", "A", "A (2)", "A")))
    }

    @Test fun resolutionIsImmutableAndPreservesExplicitSni() {
        val original = node()
        val address = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val resolved = RawUpdater.rewriteAddress(original, address, false)
        assertEquals("example.org", original.server)
        assertEquals("127.0.0.1", resolved.server)
        assertEquals("example.org", resolved.tls!!.serverName)
        val explicit = original.copy(tls = Profile.Tls(serverName = "sni.example"))
        assertEquals("sni.example", RawUpdater.rewriteAddress(explicit, address, false).tls!!.serverName)
    }

    @Test fun cancelledImportStopsBeforeCallingNative() = runBlocking {
        var cancelled = false
        val job = launch(start = CoroutineStart.LAZY) {
            currentCoroutineContext().cancel()
            try { RawUpdater.parseRaw("invalid input") }
            catch (_: CancellationException) { cancelled = true }
        }
        job.start()
        job.join()
        assertTrue(cancelled)
    }

    @Test fun automaticRefreshRejectsPartialAndEmptyButAllowsWarnings() {
        val warning = CoreClient.ImportIssue(code = "NON_PROXY_ENTRY", severity = "warning")
        assertEquals(listOf(node()), CoreClient.ImportResult(listOf(node()), listOf(warning)).requireComplete())
        for (result in listOf(CoreClient.ImportResult(), CoreClient.ImportResult(issues = listOf(warning)),
            CoreClient.ImportResult(listOf(node()), listOf(CoreClient.ImportIssue(code = "INVALID_ENTRY"))))) {
            try { result.requireComplete(); fail("Unsafe refresh accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
