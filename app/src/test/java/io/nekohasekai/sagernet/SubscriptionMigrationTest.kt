package io.nekohasekai.sagernet

import androidx.room.Room
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.SubscriptionRefresh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SubscriptionMigrationTest {
    @Test fun versionOneRetainsGroupsProfilesRulesAndCounters() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-${System.nanoTime()}"
        val schema = File("schemas/io.nekohasekai.sagernet.database.SagerDatabase/1.json")
        val database = JsonParser.parseString(schema.readText()).asJsonObject["database"].asJsonObject
        context.openOrCreateDatabase(name, 0, null).use { old ->
            database["entities"].asJsonArray.forEach { entity ->
                old.execSQL(entity.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", entity.asJsonObject["tableName"].asString))
                entity.asJsonObject["indices"]?.asJsonArray?.forEach { index ->
                    old.execSQL(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", entity.asJsonObject["tableName"].asString))
                }
            }
            database["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            // Required columns are populated from the actual v1 schema, preserving
            // the legacy on-disk shape rather than pretending a v2 DB is v1.
            database["entities"].asJsonArray.forEach { entity ->
                val e = entity.asJsonObject
                val fields = e["fields"].asJsonArray.map { it.asJsonObject }
                val values = fields.map { f ->
                    when (f["columnName"].asString) {
                        "id" -> "41"
                        "groupId" -> "41"
                        "tx" -> "123"
                        "rx" -> "456"
                        else -> if (f["notNull"]?.asBoolean != true) "NULL" else when (f["affinity"].asString) {
                            "TEXT" -> "'synthetic'"
                            "BLOB" -> "X''"
                            else -> "0"
                        }
                    }
                }
                old.execSQL("INSERT INTO `${e["tableName"].asString}` (${fields.joinToString { "`${it["columnName"].asString}`" }}) VALUES (${values.joinToString()})")
            }
            old.version = 1
        }
        val upgraded = Room.databaseBuilder(context, SagerDatabase::class.java, name)
            .allowMainThreadQueries().addMigrations(SagerDatabase.MIGRATION_1_2).build()
        try {
            val sql = upgraded.openHelper.writableDatabase // Room validates full migrated schema.
            for (table in listOf("proxy_groups", "proxy_entities", "rules")) {
                sql.query("SELECT COUNT(*) FROM `$table` WHERE id = 41").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            }
            sql.query("SELECT tx, rx FROM proxy_entities WHERE id = 41").use {
                it.moveToFirst(); assertEquals(123, it.getInt(0)); assertEquals(456, it.getInt(1))
            }
            sql.query("SELECT COUNT(*) FROM subscription_refresh_state").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            assertEquals(2, sql.version)
        } finally { upgraded.close() }
    }
}
