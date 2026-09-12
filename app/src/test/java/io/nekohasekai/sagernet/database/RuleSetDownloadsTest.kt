package io.nekohasekai.sagernet.database

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuleSetDownloadsTest {
    @get:Rule val temp = TemporaryFolder()
    private val ref = RouteRuleSet("CN", "https://example.com/cn.srs")
    @Test fun successfulDownloadIsUsedAsRemoteBootstrapAndTimestampSurvivesReopen() = runBlocking {
        val root = temp.root
        assertFalse(ref.snapshotJson { root }.has("initial_path"))
        val result = RuleSetDownloads(root).update(ref, { file, format ->
            assertEquals("binary", format); assertEquals("valid", file.readText())
        }, { _, file -> file.writeText("valid") })
        assertEquals("", result.error); assertTrue(result.checked > 0)
        assertEquals(result, RuleSetDownloads(root).status(ref))
        val snapshot = ref.snapshotJson { root }
        assertEquals(ref.source, snapshot["source"].asString)
        assertEquals(RuleSetDownloads.file(root, ref).absolutePath, snapshot["initial_path"].asString)
    }
    @Test fun invalidDownloadRetainsPreviousBytesAndSuccessfulTimestamp() = runBlocking {
        val store = RuleSetDownloads(temp.root)
        val first = store.update(ref, { _, _ -> }, { _, f -> f.writeText("old") })
        val failed = store.update(ref, { _, _ -> error("invalid SRS") }, { _, f -> f.writeText("bad") })
        assertEquals(first.checked, failed.checked)
        assertEquals("invalid SRS", RuleSetDownloads(temp.root).status(ref).error)
        assertEquals("old", RuleSetDownloads.file(temp.root, ref).readText())
        assertTrue(File(temp.root, "remote-rule-sets").listFiles()!!.none { it.extension == "tmp" })
    }
    @Test fun networkFailureIsPersistedAndLaterSuccessClearsIt() = runBlocking {
        val store = RuleSetDownloads(temp.root)
        assertEquals("HTTP 503", store.update(ref, { _, _ -> }, { _, _ -> error("HTTP 503") }).error)
        assertFalse(RuleSetDownloads.file(temp.root, ref).exists())
        assertEquals("", store.update(ref, { _, _ -> }, { _, f -> f.writeText("ok") }).error)
    }
    @Test fun cancelledDownloadCannotReplaceOldFile() = runBlocking {
        val store = RuleSetDownloads(temp.root)
        store.update(ref, { _, _ -> }, { _, f -> f.writeText("old") })
        try {
            store.update(ref, { _, _ -> }, { _, f -> f.writeText("new"); throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("old", RuleSetDownloads.file(temp.root, ref).readText())
    }
    @Test fun listIncludesDisabledReferencesAndDeduplicatesByUrlAndFormatNotName() {
        val same = ref.copy(name = "Other", match = "source")
        val different = ref.copy(source = "https://other.example/cn.srs")
        val rows = listOf(RuleEntity(enabled = false, ruleSets = RouteRuleSet.encode(listOf(ref))),
            RuleEntity(ruleSets = RouteRuleSet.encode(listOf(same, different))))
        assertEquals(listOf(ref, different), RuleSetDownloads.references(rows))
        assertNotEquals(RuleSetDownloads.key(ref), RuleSetDownloads.key(different))
    }
}
