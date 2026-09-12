package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import org.junit.Assert.*
import org.junit.Test

class DomainConfigSnapshotTest {
    private fun node(id: Long) = ProxyEntity(id = id, groupId = 1).putProfile(Profile(id = "persisted-$id", type = "vmess", server = "example.com", port = 443,
        vmess = Profile.VMess(uuid = "test"), transport = Profile.Transport(type = "ws", headers = mapOf("X-Test" to listOf("one", "two")))))

    @Test fun snapshotKeepsProfileFieldsAndExplicitCandidateOrder() {
        val rows = linkedMapOf(8L to node(8), 2L to node(2))
        val request = ConfigSnapshot.assemble(8, rows, emptyMap(), listOf(8, 2), emptyList(), ConfigSnapshot.obj(), ConfigSnapshot.obj(), "run")
        assertEquals(listOf("8", "2"), request.getAsJsonArray("selector_ids").map { it.asString })
        val profile = request.getAsJsonArray("profiles")[0].asJsonObject
        assertEquals("node:8", profile["id"].asString)
        assertEquals(2, profile.getAsJsonObject("transport").getAsJsonObject("headers").getAsJsonArray("X-Test").size())
        assertEquals("persisted-8", rows.getValue(8).requireProfile().id)
    }

    @Test fun missingChainReferencesFailAtBoundary() {
        val chain = ProxyEntity(id = 1, document = ProfileDocument.encode(ProfileDocument(kind = "chain", hops = listOf(99))))
        assertThrows(IllegalArgumentException::class.java) {
            ConfigSnapshot.assemble(1, mapOf(1L to chain), emptyMap(), listOf(1), emptyList(), ConfigSnapshot.obj(), ConfigSnapshot.obj(), "run")
        }
    }

    @Test fun ruleSetDirectionsBecomeAdjacentRulesWithSameAction() {
        val rules = listOf(RuleEntity(id = 4, domains = "domain:example.com", outbound = -1, ruleSets = RouteRuleSet.encode(listOf(
            RouteRuleSet("destination", "/tmp/destination.srs"), RouteRuleSet("source", "/tmp/source.srs", match = "source")))),
            RuleEntity(id = 5, domains = "full:next.example", outbound = -2))
        val request = ConfigSnapshot.assemble(1, mapOf(1L to node(1)), emptyMap(), listOf(1), rules, ConfigSnapshot.obj(), ConfigSnapshot.obj(), "run")
        val output = request.getAsJsonObject("policy").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf("4:0", "4:1", "5:0"), output.map { it["id"].asString })
        assertEquals(listOf(false, true), output.take(2).map { it.getAsJsonObject("match")["rule_set_ip_cidr_match_source"].asBoolean })
        assertEquals("direct", output[1].getAsJsonObject("target")["kind"].asString)
        assertEquals(2, request.getAsJsonArray("rule_sets").size())
    }

    @Test fun portRangesAndRegexRemainCompact() {
        val match = ConfigSnapshot.match(RuleEntity(domains = "regexp:^a{1,3}\\.example$", port = "443,1024:65535", sourcePort = ":1023"), emptyList())
        assertEquals(listOf(443), match.getAsJsonArray("ports").map { it.asInt })
        assertEquals("1024:65535", match.getAsJsonArray("port_ranges")[0].asString)
        assertEquals("^a{1,3}\\.example$", match.getAsJsonArray("domain_regexes")[0].asString)
    }

    @Test fun dnsUrlBecomesStructuredAndCredentialsAreRejected() {
        val dns = ConfigSnapshot.dns("https://dns.example:8443/dns-query")
        assertEquals("https", dns["type"].asString)
        assertEquals(8443, dns["port"].asInt)
        assertEquals("/dns-query", dns["path"].asString)
        assertThrows(IllegalArgumentException::class.java) { ConfigSnapshot.dns("https://user:secret@dns.example/dns-query") }
    }
    @Test fun rawOutboundRemainsAnIndependentTypedReference() {
        val raw = ProxyEntity(id = 9, document = ProfileDocument.encode(ProfileDocument(kind = "raw_config", scope = "outbound", content = """{"type":"http","server":"proxy.example","server_port":8080}""")))
        val request = ConfigSnapshot.assemble(9, mapOf(9L to raw), emptyMap(), listOf(9), emptyList(), ConfigSnapshot.obj(), ConfigSnapshot.obj(), "run")
        assertEquals(0, request.getAsJsonArray("profiles").size())
        val outbound = request.getAsJsonArray("raw_outbounds")[0].asJsonObject
        assertEquals("node:9", outbound["id"].asString)
        assertEquals("http", outbound.getAsJsonObject("json")["type"].asString)
    }
    @Test fun sniffSettingUsesBooleanWireAndRejectsUnsupportedOverride() {
        assertFalse(ConfigSnapshot.sniff(0))
        assertTrue(ConfigSnapshot.sniff(1))
        assertThrows(IllegalStateException::class.java) { ConfigSnapshot.sniff(2) }
    }
}
