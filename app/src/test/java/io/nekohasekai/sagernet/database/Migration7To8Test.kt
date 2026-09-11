package io.nekohasekai.sagernet.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Migration7To8Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SagerDatabase::class.java,
        listOf(SagerDatabase.Migration6To7(), SagerDatabase.Migration7To8()), FrameworkSQLiteOpenHelperFactory())

    @Test fun realRoomUpgradePreservesIdentityOrderAndPolicy() {
        val name = "native-rule-migration"
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO rules (id,name,config,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,outbound,packages) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(42, "test", "", 9, 1, "geosite:cn", "geoip:us,geoip:private", "443", "1234", "tcp", "geoip:cn", "tls", -1, "[]"))
            execSQL("INSERT INTO rules (id,name,config,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,outbound,packages) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf<Any>(43, "needs review", "", 10, 0, "https://example.com/custom.srs,example.com", "", "", "", "", "", "", -2, "[]"))
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true).use { db ->
            db.query("SELECT * FROM rules WHERE id=42").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(9, c.getInt(c.getColumnIndexOrThrow("userOrder")))
                assertEquals(1, c.getInt(c.getColumnIndexOrThrow("enabled")))
                assertEquals(-1, c.getInt(c.getColumnIndexOrThrow("outbound")))
                assertEquals("443", c.getString(c.getColumnIndexOrThrow("port")))
                assertEquals("1234", c.getString(c.getColumnIndexOrThrow("sourcePort")))
                assertEquals("tls", c.getString(c.getColumnIndexOrThrow("protocol")))
                assertEquals("", c.getString(c.getColumnIndexOrThrow("domains")))
                assertEquals("", c.getString(c.getColumnIndexOrThrow("ip")))
                assertEquals(1, c.getInt(c.getColumnIndexOrThrow("ipIsPrivate")))
                val refs = RouteRuleSet.decode(c.getString(c.getColumnIndexOrThrow("ruleSets")))
                assertEquals(3, refs.size)
                assertEquals(1, refs.count { it.match == "source" })
                assertTrue(refs.all { it.source.startsWith("https://") && it.source.endsWith(".srs") })
            }
            db.query("SELECT domains,ruleSets,enabled FROM rules WHERE id=43").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("https://example.com/custom.srs,example.com", c.getString(0))
                assertEquals("[]", c.getString(1))
                assertEquals(0, c.getInt(2))
            }
        }
    }

    @Test fun migrationIsOneTimeAndNativeRoundTripsWithoutExpansion() {
        val original = RuleEntity(id = 7, domains = "geosite:cn", source = "geoip:private", port = "443")
        val migrated = RouteRulesMigration.migrate(original.copy())
        assertEquals(migrated, RouteRulesMigration.migrate(migrated.copy()))
        assertTrue(migrated.sourceIpIsPrivate)
        RouteRuleSet.validateRule(migrated)
        assertTrue(runCatching { RouteRuleSet.validateRule(original) }.isFailure)
        assertTrue(runCatching { RouteRuleSet("database", "https://example.com/geoip.db").validate() }.isFailure)
        assertTrue(runCatching { RouteRuleSet("bad", "http://example.com/rules.srs").validate() }.isFailure)
    }

    @Test fun nativeDaoRetainsEnabledFilteringAndOrder() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.rulesDao()
            dao.insert(listOf(RuleEntity(id=1, enabled=true, userOrder=20), RuleEntity(id=2, enabled=false, userOrder=0), RuleEntity(id=3, enabled=true, userOrder=10)))
            assertEquals(listOf(3L,1L), dao.enabledRules().map { it.id })
            dao.updateEnabled(2, true)
            assertEquals(listOf(2L,3L,1L), dao.enabledRules().map { it.id })
        } finally { db.close() }
    }

    @Test fun localReferencesArePortableAndCannotEscapeImportDirectory() {
        val ref = RouteRuleSet("local", "rule-sets/example.srs").validate()
        assertEquals("/new-user/files/rule-sets/example.srs", ref.snapshotJson { java.io.File("/new-user/files") }["source"].asString)
        assertEquals("rule-sets/example.srs", RouteRuleSet.decode(RouteRuleSet.encode(listOf(ref))).single().source)
        assertTrue(runCatching { ref.copy(source="rule-sets/../secrets.srs").validate() }.isFailure)
    }
}
