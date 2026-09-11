package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 8,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = SagerDatabase.Migration6To7::class),
        AutoMigration(from = 7, to = 8, spec = SagerDatabase.Migration7To8::class)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    class Migration7To8 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Keep ambiguous rows untouched and editable. The native generator rejects
            // their old input explicitly; it never guesses or drops a condition.
            db.query("SELECT id, domains, ip, source, port, sourcePort, network, protocol, packages, config FROM rules").use { cursor ->
                while (cursor.moveToNext()) {
                    val original = RuleEntity(id = cursor.getLong(0), domains = cursor.getString(1), ip = cursor.getString(2), source = cursor.getString(3),
                        port = cursor.getString(4), sourcePort = cursor.getString(5), network = cursor.getString(6), protocol = cursor.getString(7),
                        packages = StringCollectionConverter.toSet(cursor.getString(8)), config = cursor.getString(9))
                    val row = try { RouteRulesMigration.migrate(original.copy()) } catch (_: IllegalArgumentException) { continue }
                    db.execSQL("UPDATE rules SET domains=?, ip=?, source=?, ruleSets=?, ipIsPrivate=?, sourceIpIsPrivate=? WHERE id=?",
                        arrayOf<Any>(row.domains, row.ip, row.source, row.ruleSets, row.ipIsPrivate, row.sourceIpIsPrivate, row.id))
                }
            }
        }
    }

    @DeleteColumn(tableName = "proxy_entities", columnName = "trojanGoBean")
    @DeleteColumn(tableName = "proxy_entities", columnName = "mieruBean")
    @DeleteColumn(tableName = "proxy_entities", columnName = "naiveBean")
    @DeleteColumn(tableName = "proxy_entities", columnName = "sshBean")
    @DeleteColumn(tableName = "proxy_entities", columnName = "nekoBean")
    class Migration6To7 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Delete removed protocol rows (Trojan-Go: 7, Naive: 9, SSH: 17, Mieru: 21, Neko: 999)
            db.execSQL("DELETE FROM proxy_entities WHERE type IN (7, 9, 17, 21, 999)")
            db.execSQL("UPDATE proxy_groups SET frontProxy = -1 WHERE frontProxy NOT IN (SELECT id FROM proxy_entities)")
            db.execSQL("UPDATE proxy_groups SET landingProxy = -1 WHERE landingProxy NOT IN (SELECT id FROM proxy_entities)")
        }
    }

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
//                .addMigrations(*SagerDatabase_Migrations.build())
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
