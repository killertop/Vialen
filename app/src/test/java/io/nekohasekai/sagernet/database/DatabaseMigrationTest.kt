package io.nekohasekai.sagernet.database

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import java.sql.ResultSet

class DatabaseMigrationTest {

    @Test
    fun testMigrate6To7CleansOldProtocolsAndPreservesValidProfiles() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val statement = connection.createStatement()

        // 1. Create Schema version 6 as defined in 6.json
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS `proxy_groups` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `userOrder` INTEGER NOT NULL,
                `ungrouped` INTEGER NOT NULL,
                `name` TEXT,
                `type` INTEGER NOT NULL,
                `subscription` BLOB,
                `order` INTEGER NOT NULL,
                `isSelector` INTEGER NOT NULL,
                `frontProxy` INTEGER NOT NULL,
                `landingProxy` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS `proxy_entities` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `groupId` INTEGER NOT NULL,
                `type` INTEGER NOT NULL,
                `userOrder` INTEGER NOT NULL,
                `tx` INTEGER NOT NULL,
                `rx` INTEGER NOT NULL,
                `status` INTEGER NOT NULL,
                `ping` INTEGER NOT NULL,
                `uuid` TEXT NOT NULL,
                `error` TEXT,
                `socksBean` BLOB,
                `httpBean` BLOB,
                `ssBean` BLOB,
                `vmessBean` BLOB,
                `trojanBean` BLOB,
                `trojanGoBean` BLOB,
                `mieruBean` BLOB,
                `naiveBean` BLOB,
                `hysteriaBean` BLOB,
                `tuicBean` BLOB,
                `sshBean` BLOB,
                `wgBean` BLOB,
                `shadowTLSBean` BLOB,
                `anyTLSBean` BLOB,
                `chainBean` BLOB,
                `nekoBean` BLOB,
                `configBean` BLOB
            )
            """.trimIndent()
        )

        statement.execute("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")

        // 2. Insert test data in version 6
        // Group with frontProxy pointing to 101 (Trojan-Go)
        statement.execute(
            "INSERT INTO proxy_groups (id, userOrder, ungrouped, name, type, \"order\", isSelector, frontProxy, landingProxy) " +
                    "VALUES (1, 1, 0, 'Default', 0, 0, 0, 101, -1)"
        )

        // Old deleted protocols: Trojan-Go (7), Naive (9), SSH (17), Mieru (21), Neko (999)
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (101, 1, 7, 1, 0, 0, 0, 0, 'uuid-trojan-go')")
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (102, 1, 9, 2, 0, 0, 0, 0, 'uuid-naive')")
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (103, 1, 17, 3, 0, 0, 0, 0, 'uuid-ssh')")
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (104, 1, 21, 4, 0, 0, 0, 0, 'uuid-mieru')")
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (105, 1, 999, 5, 0, 0, 0, 0, 'uuid-neko')")

        // Valid retained protocols: Shadowsocks (3), VMess (4)
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (200, 1, 3, 6, 100, 200, 1, 50, 'uuid-ss')")
        statement.execute("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES (201, 1, 4, 7, 300, 400, 1, 60, 'uuid-vmess')")

        // 3. Execute Room's AutoMigration 6 -> 7 (as generated in SagerDatabase_AutoMigration_6_7_Impl)
        statement.execute(
            "CREATE TABLE IF NOT EXISTS `_new_proxy_entities` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `type` INTEGER NOT NULL, " +
                    "`userOrder` INTEGER NOT NULL, `tx` INTEGER NOT NULL, `rx` INTEGER NOT NULL, `status` INTEGER NOT NULL, " +
                    "`ping` INTEGER NOT NULL, `uuid` TEXT NOT NULL, `error` TEXT, `socksBean` BLOB, `httpBean` BLOB, " +
                    "`ssBean` BLOB, `vmessBean` BLOB, `trojanBean` BLOB, `hysteriaBean` BLOB, `tuicBean` BLOB, `wgBean` BLOB, " +
                    "`shadowTLSBean` BLOB, `anyTLSBean` BLOB, `chainBean` BLOB, `configBean` BLOB)"
        )
        statement.execute(
            "INSERT INTO `_new_proxy_entities` (`id`,`groupId`,`type`,`userOrder`,`tx`,`rx`,`status`,`ping`,`uuid`,`error`,`socksBean`,`httpBean`,`ssBean`,`vmessBean`,`trojanBean`,`hysteriaBean`,`tuicBean`,`wgBean`,`shadowTLSBean`,`anyTLSBean`,`chainBean`,`configBean`) " +
                    "SELECT `id`,`groupId`,`type`,`userOrder`,`tx`,`rx`,`status`,`ping`,`uuid`,`error`,`socksBean`,`httpBean`,`ssBean`,`vmessBean`,`trojanBean`,`hysteriaBean`,`tuicBean`,`wgBean`,`shadowTLSBean`,`anyTLSBean`,`chainBean`,`configBean` FROM `proxy_entities`"
        )
        statement.execute("DROP TABLE `proxy_entities`")
        statement.execute("ALTER TABLE `_new_proxy_entities` RENAME TO `proxy_entities`")
        statement.execute("CREATE INDEX IF NOT EXISTS `groupId` ON `proxy_entities` (`groupId`)")

        // Execute SagerDatabase.Migration6To7.onPostMigrate
        val mockDb = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = mutableListOf<String>()
        every { mockDb.execSQL(capture(sqlSlot)) } answers {
            statement.execute(sqlSlot.last())
        }
        val spec = SagerDatabase.Migration6To7()
        spec.onPostMigrate(mockDb)

        // 4. Verify post-migration state
        // Check columns in proxy_entities
        val tableInfo = statement.executeQuery("PRAGMA table_info(proxy_entities)")
        val columnNames = mutableListOf<String>()
        while (tableInfo.next()) {
            columnNames.add(tableInfo.getString("name"))
        }
        tableInfo.close()

        // 5 deleted columns MUST NOT be in version 7 table
        assertFalse("trojanGoBean should be deleted", columnNames.contains("trojanGoBean"))
        assertFalse("mieruBean should be deleted", columnNames.contains("mieruBean"))
        assertFalse("naiveBean should be deleted", columnNames.contains("naiveBean"))
        assertFalse("sshBean should be deleted", columnNames.contains("sshBean"))
        assertFalse("nekoBean should be deleted", columnNames.contains("nekoBean"))

        // Retained columns MUST be present
        assertTrue("id should be present", columnNames.contains("id"))
        assertTrue("ssBean should be present", columnNames.contains("ssBean"))
        assertTrue("vmessBean should be present", columnNames.contains("vmessBean"))
        assertTrue("hysteriaBean should be present", columnNames.contains("hysteriaBean"))

        // Verify rows in proxy_entities
        val entityResult: ResultSet = statement.executeQuery("SELECT id, type, uuid, tx, rx FROM proxy_entities ORDER BY id")
        val remaining = mutableListOf<Triple<Long, Int, String>>()
        while (entityResult.next()) {
            remaining.add(Triple(entityResult.getLong("id"), entityResult.getInt("type"), entityResult.getString("uuid")))
        }
        entityResult.close()

        // Deleted protocol rows MUST be removed
        assertEquals("Exactly 2 valid profiles should remain", 2, remaining.size)
        assertEquals(200L, remaining[0].first)
        assertEquals(3, remaining[0].second) // Shadowsocks
        assertEquals("uuid-ss", remaining[0].third)

        assertEquals(201L, remaining[1].first)
        assertEquals(4, remaining[1].second) // VMess
        assertEquals("uuid-vmess", remaining[1].third)

        // Verify proxy_groups frontProxy was reset from pointing to deleted entity 101 to -1
        val groupResult = statement.executeQuery("SELECT id, frontProxy, landingProxy FROM proxy_groups WHERE id = 1")
        assertTrue(groupResult.next())
        assertEquals(-1L, groupResult.getLong("frontProxy"))
        assertEquals(-1L, groupResult.getLong("landingProxy"))
        groupResult.close()

        connection.close()
    }
}
