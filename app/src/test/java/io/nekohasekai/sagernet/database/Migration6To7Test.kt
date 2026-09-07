package io.nekohasekai.sagernet.database

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.sql.DriverManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Migration6To7Test {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java,
        listOf(SagerDatabase.Migration6To7()),
        FrameworkSQLiteOpenHelperFactory()
    )

    // =============================================================
    // 1. LITERAL_SQL_TEST
    // =============================================================
    @Test
    fun testLiteralSqlMigrationAndTypeIntegrity() {
        val db = android.database.sqlite.SQLiteDatabase.create(null)

        db.execSQL("""
            CREATE TABLE proxy_groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                userOrder INTEGER NOT NULL,
                ungrouped INTEGER NOT NULL,
                name TEXT,
                type INTEGER NOT NULL,
                subscription BLOB,
                `order` INTEGER NOT NULL,
                isSelector INTEGER NOT NULL,
                frontProxy INTEGER NOT NULL,
                landingProxy INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE proxy_entities (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                groupId INTEGER NOT NULL,
                type INTEGER NOT NULL,
                userOrder INTEGER NOT NULL,
                tx INTEGER NOT NULL,
                rx INTEGER NOT NULL,
                status INTEGER NOT NULL,
                ping INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                error TEXT,
                socksBean BLOB,
                httpBean BLOB,
                ssBean BLOB,
                vmessBean BLOB,
                trojanBean BLOB,
                trojanGoBean BLOB,
                mieruBean BLOB,
                naiveBean BLOB,
                hysteriaBean BLOB,
                tuicBean BLOB,
                sshBean BLOB,
                wgBean BLOB,
                shadowTLSBean BLOB,
                anyTLSBean BLOB,
                chainBean BLOB,
                nekoBean BLOB,
                configBean BLOB
            )
        """.trimIndent())

        // Insert surviving rows with literal type IDs
        val surviving = listOf(
            Pair(101L, 0),    // SOCKS
            Pair(102L, 1),    // HTTP
            Pair(103L, 2),    // SS
            Pair(104L, 4),    // VMess / VLESS
            Pair(105L, 6),    // Trojan
            Pair(106L, 8),    // Chain
            Pair(107L, 15),   // Hysteria
            Pair(108L, 18),   // WireGuard
            Pair(109L, 19),   // ShadowTLS
            Pair(110L, 20),   // TUIC
            Pair(111L, 22),   // AnyTLS
            Pair(112L, 998)   // Config
        )

        for ((id, type) in surviving) {
            db.execSQL("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES ($id, 1, $type, $id, 0, 0, 0, 0, 'uuid-$id')")
        }

        // Insert removed rows with literal historical type IDs
        val removed = listOf(
            Pair(201L, 7),    // Trojan-Go
            Pair(202L, 9),    // NaiveProxy
            Pair(203L, 17),   // SSH
            Pair(204L, 21),   // Mieru
            Pair(205L, 999)   // Neko Custom Plugin
        )

        for ((id, type) in removed) {
            db.execSQL("INSERT INTO proxy_entities (id, groupId, type, userOrder, tx, rx, status, ping, uuid) VALUES ($id, 1, $type, $id, 0, 0, 0, 0, 'uuid-$id')")
        }

        // Execute Migration 6 -> 7 cleanup logic
        db.execSQL("DELETE FROM proxy_entities WHERE type IN (7, 9, 17, 21, 999)")

        // Assert remaining rows
        val cursor = db.rawQuery("SELECT id, type FROM proxy_entities ORDER BY id ASC", null)
        val remaining = mutableListOf<Pair<Long, Int>>()
        while (cursor.moveToNext()) {
            remaining.add(Pair(cursor.getLong(0), cursor.getInt(1)))
        }
        cursor.close()

        assertEquals("Exactly 12 surviving protocols must remain", 12, remaining.size)
        assertEquals(surviving, remaining)

        val checkRemoved = db.rawQuery("SELECT count(*) FROM proxy_entities WHERE type IN (7, 9, 17, 21, 999)", null)
        assertTrue(checkRemoved.moveToFirst())
        assertEquals(0, checkRemoved.getInt(0))
        checkRemoved.close()

        db.close()
    }

    // =============================================================
    // 2. REAL_ROOM_MIGRATION_TEST
    // =============================================================
    @Test
    fun testRealRoom6To7MigrationAndPayloadIntegrity() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        println("[DEBUG_ASSETS] context assets: " + context.assets.list("")?.toList())
        println("[DEBUG_ASSETS] targetContext assets: " + targetContext.assets.list("")?.toList())

        // Step A: Create database with Room schema version 6 (from schemas/6.json)
        val dbV6 = helper.createDatabase(TEST_DB, 6)

        // Step B: Insert surviving literal IDs with real Kryo serialized bean payloads
        val vmessBytes = KryoConverters.serialize(VMessBean().apply {
            initializeDefaultValues()
            serverAddress = "vmess.example.com"
            serverPort = 443
            uuid = "a3424107-160a-4286-9051-7d1c5a93b482"
            alterId = 0
            type = "ws"
            host = "vmess.example.com"
            path = "/ws"
            security = "tls"
        })

        val trojanBytes = KryoConverters.serialize(TrojanBean().apply {
            initializeDefaultValues()
            serverAddress = "trojan.example.com"
            serverPort = 443
            password = "trojanpassword123"
            sni = "trojan.example.com"
        })

        val hysteriaBytes = KryoConverters.serialize(HysteriaBean().apply {
            initializeDefaultValues()
            protocolVersion = 1
            serverAddress = "hy1.example.com"
            serverPorts = "36712"
            authPayload = "myhyauthpass"
            protocol = HysteriaBean.PROTOCOL_UDP
        })

        val wireguardBytes = KryoConverters.serialize(WireGuardBean().apply {
            initializeDefaultValues()
            serverAddress = "wg.example.com"
            serverPort = 51820
            privateKey = "privkey-AAAAAAAAAAAAAAAA"
            peerPublicKey = "pubkey-BBBBBBBBBBBBBBBB"
            localAddress = "10.0.0.2/32"
            mtu = 1420
        })

        val anytlsBytes = KryoConverters.serialize(AnyTLSBean().apply {
            initializeDefaultValues()
            serverAddress = "anytls.example.com"
            serverPort = 443
            password = "anypassword456"
            sni = "anytls.example.com"
        })

        val chainBytes = KryoConverters.serialize(ChainBean().apply {
            initializeDefaultValues()
            name = "MyChain"
            proxies = mutableListOf(101L, 102L)
        })

        val configBytes = KryoConverters.serialize(ConfigBean().apply {
            initializeDefaultValues()
            name = "CustomConfig"
            config = "{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]}"
        })

        val survivingRows = listOf(
            Triple(101L, 0, null),                      // SOCKS
            Triple(102L, 1, null),                      // HTTP
            Triple(103L, 2, null),                      // SS
            Triple(104L, 4, vmessBytes),                // VMess (literal type = 4)
            Triple(105L, 6, trojanBytes),               // Trojan (literal type = 6)
            Triple(106L, 8, chainBytes),                // Chain (literal type = 8)
            Triple(107L, 15, hysteriaBytes),            // Hysteria (literal type = 15)
            Triple(108L, 18, wireguardBytes),           // WireGuard (literal type = 18)
            Triple(109L, 19, null),                     // ShadowTLS (literal type = 19)
            Triple(110L, 20, null),                     // TUIC (literal type = 20)
            Triple(111L, 22, anytlsBytes),              // AnyTLS (literal type = 22)
            Triple(112L, 998, configBytes)              // Config (literal type = 998)
        )

        for ((id, type, blob) in survivingRows) {
            val cv = ContentValues().apply {
                put("id", id)
                put("groupId", 1L)
                put("type", type)
                put("userOrder", id)
                put("tx", 0L)
                put("rx", 0L)
                put("status", 0)
                put("ping", 0)
                put("uuid", "uuid-$id")
                when (type) {
                    4 -> put("vmessBean", blob)
                    6 -> put("trojanBean", blob)
                    8 -> put("chainBean", blob)
                    15 -> put("hysteriaBean", blob)
                    18 -> put("wgBean", blob)
                    22 -> put("anyTLSBean", blob)
                    998 -> put("configBean", blob)
                }
            }
            dbV6.insert("proxy_entities", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
        }

        // Insert removed literal IDs (7, 9, 17, 21, 999) with dummy legacy columns
        val removedRows = listOf(
            Pair(201L, 7),    // Trojan-Go
            Pair(202L, 9),    // Naive
            Pair(203L, 17),   // SSH
            Pair(204L, 21),   // Mieru
            Pair(205L, 999)   // Neko
        )

        for ((id, type) in removedRows) {
            val cv = ContentValues().apply {
                put("id", id)
                put("groupId", 1L)
                put("type", type)
                put("userOrder", id)
                put("tx", 0L)
                put("rx", 0L)
                put("status", 0)
                put("ping", 0)
                put("uuid", "uuid-$id")
            }
            dbV6.insert("proxy_entities", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, cv)
        }

        dbV6.close()

        // Step C: Run real Room Migration 6 -> 7 and validate schema 7
        val dbV7 = helper.runMigrationsAndValidate(TEST_DB, 7, true)

        // Step D: Validate Room schema and surviving / removed row counts
        val removedCursor = dbV7.query("SELECT count(*) FROM proxy_entities WHERE type IN (7, 9, 17, 21, 999)")
        assertTrue("Cursor must open", removedCursor.moveToFirst())
        assertEquals("Removed protocol rows must be completely deleted", 0, removedCursor.getInt(0))
        removedCursor.close()

        val survivingCursor = dbV7.query("SELECT id, type, vmessBean, trojanBean, chainBean, hysteriaBean, wgBean, anyTLSBean, configBean FROM proxy_entities ORDER BY id ASC")
        val resultTypes = mutableListOf<Pair<Long, Int>>()

        while (survivingCursor.moveToNext()) {
            val id = survivingCursor.getLong(0)
            val type = survivingCursor.getInt(1)
            resultTypes.add(Pair(id, type))

            when (type) {
                4 -> { // VMess
                    val blob = survivingCursor.getBlob(2)
                    assertNotNull("VMess bean blob must not be null", blob)
                    val bean = KryoConverters.vmessDeserialize(blob)
                    assertEquals("vmess.example.com", bean.serverAddress)
                    assertEquals("a3424107-160a-4286-9051-7d1c5a93b482", bean.uuid)
                }
                6 -> { // Trojan
                    val blob = survivingCursor.getBlob(3)
                    assertNotNull("Trojan bean blob must not be null", blob)
                    val bean = KryoConverters.trojanDeserialize(blob)
                    assertEquals("trojan.example.com", bean.serverAddress)
                    assertEquals("trojanpassword123", bean.password)
                }
                8 -> { // Chain
                    val blob = survivingCursor.getBlob(4)
                    assertNotNull("Chain bean blob must not be null", blob)
                    val bean = KryoConverters.chainDeserialize(blob)
                    assertEquals(listOf(101L, 102L), bean.proxies)
                }
                15 -> { // Hysteria
                    val blob = survivingCursor.getBlob(5)
                    assertNotNull("Hysteria bean blob must not be null", blob)
                    val bean = KryoConverters.hysteriaDeserialize(blob)
                    assertEquals("hy1.example.com", bean.serverAddress)
                    assertEquals("myhyauthpass", bean.authPayload)
                }
                18 -> { // WireGuard
                    val blob = survivingCursor.getBlob(6)
                    assertNotNull("WireGuard bean blob must not be null", blob)
                    val bean = KryoConverters.wireguardDeserialize(blob)
                    assertEquals("wg.example.com", bean.serverAddress)
                    assertEquals("privkey-AAAAAAAAAAAAAAAA", bean.privateKey)
                    assertEquals("pubkey-BBBBBBBBBBBBBBBB", bean.peerPublicKey)
                }
                22 -> { // AnyTLS
                    val blob = survivingCursor.getBlob(7)
                    assertNotNull("AnyTLS bean blob must not be null", blob)
                    val bean = KryoConverters.anyTLSDeserialize(blob)
                    assertEquals("anytls.example.com", bean.serverAddress)
                    assertEquals("anypassword456", bean.password)
                }
                998 -> { // Config
                    val blob = survivingCursor.getBlob(8)
                    assertNotNull("Config bean blob must not be null", blob)
                    val bean = KryoConverters.configDeserialize(blob)
                    assertTrue("Config json preserved", bean.config.contains("direct"))
                }
            }
        }
        survivingCursor.close()

        val expectedSurviving = survivingRows.map { Pair(it.first, it.second) }
        assertEquals("Surviving row types must match expected literal list", expectedSurviving, resultTypes)

        dbV7.close()
    }
}
