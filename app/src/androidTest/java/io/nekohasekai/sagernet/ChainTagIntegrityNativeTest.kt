package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigSnapshot
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Graph validity and actual core start, deliberately independent of the Kotlin oracle. */
@RunWith(AndroidJUnit4::class)
class ChainTagIntegrityNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val benchmarkForeground = BenchmarkForegroundRule()
    @Test fun reusedGlobalNodesAreReferencedByExistingTagsAndStartInPackagedCore() {
        val db = SagerDatabase.instance
        val oldMode = DataStore.serviceMode; val oldDirect = DataStore.directDns
        val oldRemote = DataStore.remoteDns; val oldPort = DataStore.mixedPort
        val oldCustom = DataStore.globalCustomConfig; val oldClash = DataStore.enableClashAPI
        val group = ProxyGroup(name = "tag-integrity-${System.nanoTime()}")
        group.id = db.groupDao().createGroup(group)
        var ruleId = 0L
        try {
            DataStore.serviceMode = Key.MODE_PROXY; DataStore.directDns = "local"
            DataStore.remoteDns = "local"; DataStore.globalCustomConfig = ""
            DataStore.enableClashAPI = false
            java.net.ServerSocket(0).use { DataStore.mixedPort = it.localPort }
            fun node(name: String): ProxyEntity = ProxyEntity(groupId = group.id).apply {
                putBean(SOCKSBean().applyDefaultValues().apply {
                    this.name = name; serverAddress = "127.0.0.1"; serverPort = 1080
                })
                id = db.proxyDao().addProxy(this)
            }
            val first = node("first"); val second = node("second")
            val chain = ProxyEntity(groupId = group.id).apply {
                putBean(ChainBean().applyDefaultValues().apply {
                    name = "chain"; proxies = mutableListOf(first.id, second.id)
                })
                id = db.proxyDao().addProxy(this)
            }
            ruleId = db.rulesDao().createRule(RuleEntity(name = "reuse", enabled = true, domains = "full:chain.invalid", outbound = chain.id))
            assertTrue("Fixture rule must enter the captured route graph", db.rulesDao().getById(ruleId)!!.enabled)
            for (selector in listOf(false, true)) {
                group.isSelector = selector; db.groupDao().updateGroup(group)
                val result = ConfigSnapshot.capture(first, false, false).generate().result
                val config = JsonParser.parseString(result.config).asJsonObject
                val tags = (config["outbounds"].asJsonArray + config["endpoints"].asJsonArray)
                    .map { it.asJsonObject["tag"].asString }
                assertEquals("Unique tags", tags.size, tags.toSet().size)
                fun visit(value: JsonElement) {
                    if (value.isJsonObject) {
                        val obj = value.asJsonObject
                        obj["detour"]?.let { assertTrue("Missing detour ${it.asString}", it.asString in tags) }
                        if (obj["type"]?.asString == "selector") {
                            obj["outbounds"].asJsonArray.forEach { assertTrue(it.asString in tags) }
                            obj["default"]?.let { default ->
                                assertTrue(default.asString in tags)
                                assertTrue(obj["outbounds"].asJsonArray.any { it == default })
                            }
                        }
                        obj.entrySet().forEach { visit(it.value) }
                    } else if (value.isJsonArray) value.asJsonArray.forEach(::visit)
                }
                visit(config)
                val target=if(selector) "first" else "proxy"
                val chainTag=if(selector) "second-2" else "c-${chain.id}-${second.id}"
                val expectedEdges=if(selector) setOf("second-1", "second-2") else setOf(chainTag)
                val edges=config["outbounds"].asJsonArray.filter { it.asJsonObject.has("detour") }
                assertEquals("Exact chain edges: $config", expectedEdges,
                    edges.map { it.asJsonObject["tag"].asString }.toSet())
                edges.forEach { assertEquals(target, it.asJsonObject["detour"].asString) }
                assertEquals(chainTag, result.profileTagMap.getValue(chain.id))
                val route=config["route"].asJsonObject["rules"].asJsonArray.single {
                    it.asJsonObject["domain"]?.asJsonArray?.any { domain -> domain.asString=="chain.invalid" }==true
                }.asJsonObject
                assertEquals(chainTag, route["outbound"].asString)
                if(selector) {
                    val options=config["outbounds"].asJsonArray.single {
                        it.asJsonObject["tag"].asString=="proxy"
                    }.asJsonObject["outbounds"].asJsonArray.map { it.asString }.toSet()
                    assertEquals(setOf("first", "second", "second-1"), options)
                }
                println("TAG_GRAPH selector=$selector edges=$expectedEdges target=$target route=$chainTag")
                val core = Libcore.newSingBoxInstance(result.config, null)
                try { core.start() } finally { core.close() }
                println("TAG_INTEGRITY selector=$selector tags=${tags.size} core_started=true core_closed=true")
            }
        } finally {
            DataStore.serviceMode = oldMode; DataStore.directDns = oldDirect
            DataStore.remoteDns = oldRemote; DataStore.mixedPort = oldPort
            DataStore.globalCustomConfig = oldCustom; DataStore.enableClashAPI = oldClash
            db.runInTransaction {
                if (ruleId != 0L) db.rulesDao().deleteById(ruleId)
                db.proxyDao().deleteByGroup(group.id); db.groupDao().deleteById(group.id)
            }
        }
        assertNull(db.groupDao().getById(group.id))
    }
}
