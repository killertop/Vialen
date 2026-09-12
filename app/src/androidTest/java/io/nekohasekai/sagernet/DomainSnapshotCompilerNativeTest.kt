package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Physical-device integration: actual Room/settings capture through the packaged Go compiler. */
@RunWith(AndroidJUnit4::class)
class DomainSnapshotCompilerNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule()

    @Test fun captureCompilesBooleanSniffAndRawChainIntoRealRuntimeOptions() {
        val db = SagerDatabase.instance
        val oldSniff = DataStore.trafficSniffing
        val oldMode = DataStore.serviceMode
        val oldDirect = DataStore.directDns
        val oldRemote = DataStore.remoteDns
        val oldCustom = DataStore.globalCustomConfig
        val oldFake = DataStore.enableFakeDns
        val oldRules = db.rulesDao().enabledRules()
        val strategyKeys = listOf("domain_strategy_for_server", "domain_strategy_for_direct", "domain_strategy_for_remote")
        val oldStrategies = strategyKeys.associateWith { DataStore.configurationStore.getString(it) }
        val group = ProxyGroup(name = "Domain compiler integration ${System.nanoTime()}")
        group.id = db.groupDao().createGroup(group)
        try {
            oldRules.forEach { db.rulesDao().updateRule(it.copy(enabled = false)) }
            DataStore.serviceMode = Key.MODE_PROXY
            DataStore.directDns = "local"
            DataStore.remoteDns = "local"
            DataStore.globalCustomConfig = ""
            DataStore.enableFakeDns = false
            strategyKeys.forEach { DataStore.configurationStore.putString(it, "auto") }
            val row = ProxyEntity(groupId = group.id).putProfile(Profile(type = "socks", server = "127.0.0.1", port = 1080, socks = Profile.Socks()))
            row.id = db.proxyDao().addProxy(row)
            for (mode in listOf(0, 1)) {
                DataStore.trafficSniffing = mode
                val result = ConfigSnapshot.capture(row, false, false).generate().result
                val config = JsonParser.parseString(result.config).asJsonObject
                val actions = config.getAsJsonObject("route").getAsJsonArray("rules")?.mapNotNull { it.asJsonObject["action"]?.asString }.orEmpty()
                assertEquals(mode == 1, "sniff" in actions)
                Libcore.newSingBoxInstance(result.config, null).close()
            }
            val raw = ProxyEntity(type = ProxyEntity.TYPE_CONFIG, groupId = group.id, document = ProfileDocument.encode(ProfileDocument(kind = "raw_config", scope = "outbound", content = """{"type":"http","server":"127.0.0.1","server_port":8080}""")))
            raw.id = db.proxyDao().addProxy(raw)
            val chain = ProxyEntity(type = ProxyEntity.TYPE_CHAIN, groupId = group.id, document = ProfileDocument.encode(ProfileDocument(kind = "chain", hops = listOf(raw.id, row.id))))
            chain.id = db.proxyDao().addProxy(chain)
            val plan = ConfigSnapshot.capture(chain, false, false).generate().result
            assertEquals(setOf(chain.id, raw.id, row.id), plan.trafficMap.values.flatten().map { it.id }.toSet())
            Libcore.newSingBoxInstance(plan.config, null).close()
        } finally {
            db.proxyDao().deleteByGroup(group.id)
            db.groupDao().deleteById(group.id)
            oldRules.forEach { db.rulesDao().updateRule(it) }
            DataStore.trafficSniffing = oldSniff
            DataStore.serviceMode = oldMode
            DataStore.directDns = oldDirect
            DataStore.remoteDns = oldRemote
            DataStore.globalCustomConfig = oldCustom
            DataStore.enableFakeDns = oldFake
            oldStrategies.forEach { (key, value) -> DataStore.configurationStore.putString(key, value) }
        }
    }
}
