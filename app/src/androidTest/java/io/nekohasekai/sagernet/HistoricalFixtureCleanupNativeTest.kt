package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One-shot recovery of exact archived records from interrupted September 13 tests. */
@RunWith(AndroidJUnit4::class)
class HistoricalFixtureCleanupNativeTest {
    @Test fun recoverOnlyExplicitlyArchivedInterruptedFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("archivedFixtureCleanup") == "20260913")
        check(instrumentation.targetContext.packageName == "com.vialen.app.debug")
        check(!DataStore.serviceState.started)
        val db = SagerDatabase.instance
        val groups = mapOf(34L to "handover-159693567758143", 35L to "URL timing fixture")
        val profiles = mapOf(33L to 34L, 34L to 35L)
        val rules = mapOf(33L to "handover-159693567758143", 34L to "handover-159693567758143-health")
        db.runInTransaction {
            groups.forEach { (id, name) -> check(db.groupDao().getById(id)?.name == name) }
            profiles.forEach { (id, group) ->
                val row = checkNotNull(db.proxyDao().getById(id))
                check(row.groupId == group && row.requireProfile().server == "127.0.0.1")
                check(db.proxyDao().getIdsByGroup(group) == listOf(id))
            }
            rules.forEach { (id, name) -> check(db.rulesDao().getById(id)?.name == name) }
            profiles.keys.forEach { db.proxyDao().deleteById(it) }
            rules.keys.forEach { db.rulesDao().deleteById(it) }
            groups.keys.forEach { db.groupDao().deleteById(it) }
        }
        val prefs = PublicDatabase.kvPairDao
        PublicDatabase.instance.runInTransaction {
            if (DataStore.selectedProxy in profiles.keys) DataStore.selectedProxy = 0
            if (DataStore.currentProfile in profiles.keys) DataStore.currentProfile = 0
            if (DataStore.selectedGroup in groups.keys) DataStore.selectedGroup = 1
            if (prefs[Key.CONNECTION_TEST_URL]?.string == "http://198.18.0.254/url-dialog-160454327949467") {
                prefs.delete(Key.CONNECTION_TEST_URL)
            }
            if (DataStore.individual.lines().toSet() == setOf("com.vialen.app.debug", "com.vialen.app.debug.test")) {
                prefs.delete(Key.INDIVIDUAL)
                DataStore.proxyApps = false
            }
        }
        groups.keys.forEach { assertNull(db.groupDao().getById(it)) }
        profiles.keys.forEach { assertNull(db.proxyDao().getById(it)) }
        rules.keys.forEach { assertNull(db.rulesDao().getById(it)) }
        println("HISTORICAL_FIXTURE_CLEANUP groups=34,35 profiles=33,34 rules=33,34 archived=true exact_targets=true")
    }
}
