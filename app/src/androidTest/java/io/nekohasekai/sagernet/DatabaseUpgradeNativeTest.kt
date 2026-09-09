package io.nekohasekai.sagernet

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Device SQLite and Room-generated migrations; never opens the user's profile database. */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeNativeTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), SagerDatabase::class.java,
        listOf(SagerDatabase.Migration6To7()), FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun schemas3Through6MigrateTo7WithPayloadsAndReferences() {
        Assume.assumeTrue("Opt in on a physical device with -e vialenDatabaseUpgrade true",
            InstrumentationRegistry.getArguments().getString("vialenDatabaseUpgrade") == "true")
        for (version in 3..6) migrate(version)
    }

    private fun migrate(version: Int) {
        val name = "native-room-upgrade-$version"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        val protocols = listOf(
            Triple(0, "socksBean", SOCKSBean()), Triple(1, "httpBean", HttpBean()),
            Triple(2, "ssBean", ShadowsocksBean()), Triple(4, "vmessBean", VMessBean()),
            Triple(6, "trojanBean", TrojanBean()), Triple(8, "chainBean", ChainBean()),
            Triple(15, "hysteriaBean", HysteriaBean()), Triple(18, "wgBean", WireGuardBean()),
            Triple(19, "shadowTLSBean", ShadowTLSBean()), Triple(20, "tuicBean", TuicBean()),
            Triple(22, "anyTLSBean", AnyTLSBean()), Triple(998, "configBean", ConfigBean())
        ).filter { version >= 5 || it.first != 22 }
        val payloads = linkedMapOf<Long, Pair<String, ByteArray>>()
        try {
            helper.createDatabase(name, version).use { db ->
                db.execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,`order`,isSelector,frontProxy,landingProxy) VALUES (1,1,0,'upgrade fixture',0,0,0,201,101)")
                protocols.forEachIndexed { index, (type, column, bean) ->
                    bean.initializeDefaultValues()
                    bean.name = "native-$type"
                    bean.serverAddress = "127.0.0.1"
                    bean.serverPort = 12345
                    bean.customConfigJson = "{\"fixture\":$type}"
                    bean.customOutboundJson = "{\"preserve\":true}"
                    when (bean) {
                        is VMessBean -> { bean.uuid = "a3424107-160a-4286-9051-7d1c5a93b482"; bean.type = "ws"; bean.path = "/migration" }
                        is TrojanBean -> bean.password = "local-fixture-password"
                        is ChainBean -> bean.proxies = mutableListOf(101L, 102L)
                        is ConfigBean -> bean.config = "{\"outbounds\":[{\"type\":\"direct\"}]}"
                    }
                    val id = 101L + index
                    val bytes = KryoConverters.serialize(bean)
                    payloads[id] = column to bytes
                    db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, row(id, type).apply { put(column, bytes) })
                }
                listOf(7, 9, 17, 21, 999).forEachIndexed { index, type ->
                    db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, row(201L + index, type))
                }
                db.execSQL("INSERT INTO rules (id,name,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,outbound,packages) VALUES (1,'preserved rule',9,1,'example.test','','443','','tcp','','',101,'[]')")
            }
            // No hand-written migration SQL: this executes the generated 3->4->5->6->7 chain.
            helper.runMigrationsAndValidate(name, 7, true).use { db ->
                db.query("SELECT COUNT(*) FROM proxy_entities").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(protocols.size, c.getInt(0))
                }
                payloads.forEach { (id, payload) ->
                    db.query("SELECT ${payload.first},tx,rx,uuid FROM proxy_entities WHERE id=$id").use { c ->
                        assertTrue(c.moveToFirst())
                        assertArrayEquals("v$version id=$id payload", payload.second, c.getBlob(0))
                        assertEquals(123456L, c.getLong(1)); assertEquals(654321L, c.getLong(2))
                        assertEquals("fixture-$id", c.getString(3))
                    }
                }
                db.query("SELECT vmessBean FROM proxy_entities WHERE type=4").use { c ->
                    assertTrue(c.moveToFirst())
                    val bean = KryoConverters.vmessDeserialize(c.getBlob(0))
                    assertEquals("a3424107-160a-4286-9051-7d1c5a93b482", bean.uuid)
                    assertEquals("ws", bean.type)
                    assertEquals("/migration", bean.path)
                    assertEquals("{\"fixture\":4}", bean.customConfigJson)
                }
                db.query("SELECT frontProxy,landingProxy FROM proxy_groups WHERE id=1").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(-1L, c.getLong(0)); assertEquals(101L, c.getLong(1))
                }
                db.query("SELECT name,config,userOrder,enabled,outbound FROM rules WHERE id=1").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("preserved rule", c.getString(0))
                    assertEquals("", c.getString(1)); assertEquals(9L, c.getLong(2))
                    assertEquals(1, c.getInt(3)); assertEquals(101L, c.getLong(4))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    private fun row(id: Long, type: Int) = ContentValues().apply {
        put("id", id); put("groupId", 1L); put("type", type); put("userOrder", id)
        put("tx", 123456L); put("rx", 654321L); put("status", 0); put("ping", 0); put("uuid", "fixture-$id")
    }
}
