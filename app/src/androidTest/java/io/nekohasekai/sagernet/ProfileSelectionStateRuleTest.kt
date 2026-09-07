package io.nekohasekai.sagernet

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.internal.runners.statements.RunAfters
import org.junit.internal.runners.statements.RunBefores
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class ProfileSelectionStateRuleTest {
    @Test fun restoresRawRowsAndAbsentKeysAcrossEveryJUnitPhase() {
        listOf(false, true).forEach { absent ->
            listOf("success", "setup", "test", "teardown").forEach { phase ->
                database { db ->
                    val dao = db.keyValuePairDao()
                    if (!absent) {
                        // Include the legacy INT representation: value equality alone is insufficient.
                        dao.put(KeyValuePair(Key.PROFILE_CURRENT).apply {
                            valueType = KeyValuePair.TYPE_INT
                            value = byteArrayOf(0, 0, 0, 7)
                        })
                        dao.put(KeyValuePair(Key.PROFILE_GROUP).put(0L))
                        dao.put(KeyValuePair(Key.PROFILE_ID).put(Long.MAX_VALUE))
                        dao.put(KeyValuePair(Key.SERVICE_MODE).put("proxy"))
                        dao.put(KeyValuePair(Key.DIRECT_DNS).put("local"))
                        dao.put(KeyValuePair(Key.REMOTE_DNS).put("https://example.test/dns-query"))
                        dao.put(KeyValuePair(Key.ENABLE_DNS_ROUTING).put(false))
                    }
                    val expected = ProfileSelectionStateRule.keys.associateWith { dao[it] }
                    val fixture = LifecycleFixture(db, phase)
                    val body = object : Statement() {
                        override fun evaluate() { fixture.testBody() }
                    }
                    val lifecycle = RunAfters(
                        RunBefores(body, listOf(FrameworkMethod(LifecycleFixture::class.java.getMethod("setup"))), fixture),
                        listOf(FrameworkMethod(LifecycleFixture::class.java.getMethod("teardown"))), fixture
                    )
                    val failure = runCatching {
                        ProfileSelectionStateRule.withSnapshot(db, "$phase-absent=$absent", { lifecycle.evaluate() })
                    }.exceptionOrNull()
                    if (phase == "success") assertNull(failure) else assertSame(fixture.failure, failure)
                    assertTrue("JUnit must execute teardown after setup/test failure", fixture.toreDown)
                    expected.forEach { (key, row) ->
                        val actual = dao[key]
                        if (row == null) assertNull(actual) else {
                            assertNotNull(actual)
                            assertEquals(row.valueType, actual!!.valueType)
                            assertArrayEquals(row.value, actual.value)
                        }
                    }
                }
            }
        }
    }

    @Test fun restoresDespiteCleanupFailureAndPreservesOriginalFailure() = database { db ->
        val primary = AssertionError("test failure")
        val cleanup = AssertionError("cleanup failure")
        val thrown = runCatching {
            ProfileSelectionStateRule.withSnapshot(db, "cleanup-failure", {
                db.keyValuePairDao().put(KeyValuePair(Key.PROFILE_ID).put(123L))
                throw primary
            }, { throw cleanup })
        }.exceptionOrNull()
        assertSame(primary, thrown)
        assertTrue(primary.suppressed.contains(cleanup))
        ProfileSelectionStateRule.keys.forEach { assertNull(db.keyValuePairDao()[it]) }
    }

    @Test fun inlineCleanupFailureDoesNotReplaceOriginalAssertion() = kotlinx.coroutines.runBlocking {
        val primary = AssertionError("test failure")
        val cleanup = AssertionError("cleanup failure")
        val thrown = runCatching {
            ProfileSelectionStateRule().preservingFailure({ throw primary }, { throw cleanup })
        }.exceptionOrNull()
        assertSame(primary, thrown)
        assertTrue(primary.suppressed.contains(cleanup))
    }

    private fun database(block: (PublicDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PublicDatabase::class.java)
            .allowMainThreadQueries().build()
        try { block(db) } finally { db.close() }
    }

    class LifecycleFixture(private val db: PublicDatabase, private val phase: String) {
        val failure = AssertionError("injected $phase failure")
        var toreDown = false
        fun setup() { mutate("setup") }
        fun testBody() { mutate("test") }
        fun teardown() { toreDown = true; mutate("teardown") }
        private fun mutate(stage: String) {
            ProfileSelectionStateRule.keys.forEach { db.keyValuePairDao().put(KeyValuePair(it).put(999L)) }
            if (phase == stage) throw failure
        }
    }
}
