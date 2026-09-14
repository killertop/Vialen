package io.nekohasekai.sagernet

import androidx.room.Room
import io.mockk.*
import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RuleSaveValidationTest {
    private lateinit var db: SagerDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java).allowMainThreadQueries().build()
        mockkObject(SagerDatabase.Companion)
        every { SagerDatabase.instance } returns db
        every { SagerDatabase.rulesDao } returns db.rulesDao()
        every { SagerDatabase.proxyDao } returns db.proxyDao()
    }
    @After fun cleanup() { db.close(); unmockkAll() }
    @Test fun ruleSetMetadataCannotEnterSavedOrEnabledRules() = runBlocking {
        val saved = RuleEntity(name = "saved", domains = "example.test", enabled = true)
        saved.id = db.rulesDao().createRule(saved)
        for (url in listOf("https://example.invalid/rules.json", "https://example.invalid:65536/rules.srs",
            "https://example.invalid:0/rules.srs", "https://example.invalid")) {
            val ref = RouteRuleSet("test", url, "binary")
            assertTrue("Editor accepted $url", runCatching { ref.validate() }.isFailure)
            val raw = "[${ref.json()}]" // Legacy stored content must also be checked on enable.
            assertTrue(runCatching { ProfileManager.updateRule(saved.copy(ruleSets = raw)) }.isFailure)
            assertEquals(saved, db.rulesDao().getById(saved.id))
            assertTrue(runCatching { ProfileManager.createRule(saved.copy(id = 0, ruleSets = raw), post = false) }.isFailure)
            val legacy = saved.copy(id = 0, enabled = false, ruleSets = raw)
            legacy.id = db.rulesDao().createRule(legacy)
            assertTrue(runCatching { ProfileManager.setRuleEnabled(legacy.id, true) }.isFailure)
            assertFalse(db.rulesDao().getById(legacy.id)!!.enabled)
        }
        for ((url, format) in listOf("https://example.invalid/rules.srs" to "binary", "https://example.invalid/rules.json" to "source")) {
            val ref = RouteRuleSet("test", url, format).validate()
            ProfileManager.updateRule(saved.copy(ruleSets = RouteRuleSet.encode(listOf(ref))))
            assertEquals(url, RouteRuleSet.decode(db.rulesDao().getById(saved.id)!!.ruleSets).single().source)
        }
    }
    @Test fun invalidRuleNeverReplacesOrEnablesSavedRule() = runBlocking {
        val saved = RuleEntity(name = "old", enabled = true, domains = "example.test")
        saved.id = db.rulesDao().createRule(saved)
        for (invalid in listOf(
            saved.copy(port = "0"), saved.copy(port = "65536"), saved.copy(sourcePort = "2:1"),
            saved.copy(ip = "not-an-ip"), saved.copy(ip = "192.0.2.0/99"), saved.copy(domains = "regexp:["),
            saved.copy(domains = ""), saved.copy(protocol = "unknown"), saved.copy(outbound = 999),
            saved.copy(config = "{}"),
        )) {
            assertTrue(runCatching { ProfileManager.updateRule(invalid) }.isFailure)
            assertEquals(saved, db.rulesDao().getById(saved.id))
        }
        val legacy = saved.copy(id = 0, enabled = false, port = "0")
        legacy.id = db.rulesDao().createRule(legacy)
        assertTrue(runCatching { ProfileManager.setRuleEnabled(legacy.id, true) }.isFailure)
        assertFalse(db.rulesDao().getById(legacy.id)!!.enabled)
    }

    @Test fun singlePortBoundariesAndOpenRangesRemainCompatible() {
        for (ports in listOf("1", "65535", "1,65535", ":443", "1024:", ":", "0:65535")) {
            RouteRuleSet.validateRule(RuleEntity(port = ports))
            RouteRuleSet.validateRule(RuleEntity(sourcePort = ports))
        }
        RouteRuleSet.validateRule(RuleEntity(ipIsPrivate = true))
        RouteRuleSet.validateRule(RuleEntity(ruleSets = RouteRuleSet.encode(listOf(RouteRuleSet.official("geoip", "cn")))))
    }
}
