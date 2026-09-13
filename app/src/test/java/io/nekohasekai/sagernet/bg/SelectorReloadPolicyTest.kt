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
}
