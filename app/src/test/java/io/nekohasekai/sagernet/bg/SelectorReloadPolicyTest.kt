package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorReloadPolicyTest {
    private val tags = mapOf(1L to "node-1")

    @Test fun unchangedLoadedNodeCanReuse() {
        assertTrue(SelectorReloadPolicy.canReuse(7, 7, "config", "config", tags, tags, 1))
    }

    @Test fun changedCredentialsDnsOrRouteRequireReload() {
        for (field in listOf("password", "dns", "route")) {
            assertFalse(SelectorReloadPolicy.canReuse(7, 7,
                "{\"$field\":\"old\"}", "{\"$field\":\"new\"}", tags, tags, 1))
        }
    }

    @Test fun newOrMissingNodeRequiresReload() {
        assertFalse(SelectorReloadPolicy.canReuse(7, 7, "config", "config", tags, tags + (2L to "node-2"), 2))
        assertFalse(SelectorReloadPolicy.canReuse(7, 7, "config", "config", tags, tags, 2))
        assertFalse(SelectorReloadPolicy.canReuse(7, 8, "config", "config", tags, tags, 1))
        assertFalse(SelectorReloadPolicy.canReuse(-1, -1, "config", "config", tags, tags, 1))
    }

    @Test fun onlyLegalSelectedDefaultMayDiffer() {
        val tags = mapOf(1L to "node-1", 2L to "node-2")
        val first = """{"outbounds":[{"type":"selector","tag":"selected","outbounds":["node-1","node-2"],"default":"node-1"}],"dns":{"server":"one"}}"""
        val second = first.replace("\"default\":\"node-1\"", "\"default\":\"node-2\"")
        assertTrue(SelectorReloadPolicy.canReuse(7, 7, first, second, tags, tags, 2))
        assertFalse(SelectorReloadPolicy.canReuse(7, 7, first,
            second.replace("\"server\":\"one\"", "\"server\":\"two\""), tags, tags, 2))
        assertFalse(SelectorReloadPolicy.canReuse(7, 7, first,
            second.replace("\"default\":\"node-2\"", "\"default\":\"unknown\""), tags, tags, 2))
        assertFalse(SelectorReloadPolicy.canReuse(7, 7, first,
            second.replace("\"tag\":\"selected\"", "\"tag\":\"custom\""), tags, tags, 2))
    }
}
