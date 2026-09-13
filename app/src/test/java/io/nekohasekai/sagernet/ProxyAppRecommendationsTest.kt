package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.ProxyAppRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyAppRecommendationsTest {
    private val installed = listOf(
        ProxyAppRecommendations.App("recommended.app", 10001),
        ProxyAppRecommendations.App("shared.helper", 10001),
        ProxyAppRecommendations.App("manual.app", 10002),
        ProxyAppRecommendations.App("android", 1000),
    )

    @Test fun proxyModeAddsExactMatchesAndSharedUidWithoutClearingManualChoices() {
        val plan = ProxyAppRecommendations.plan(installed, setOf("manual.app"),
            setOf("recommended.app"), enabled = true, bypass = false)
        assertEquals(setOf("recommended.app", "shared.helper", "manual.app"), plan.packages)
        assertEquals(2, plan.matched)
        assertEquals(2, plan.changed)
    }

    @Test fun bypassModeRemovesRecommendedUidAndKeepsOtherChoices() {
        val plan = ProxyAppRecommendations.plan(installed,
            setOf("recommended.app", "shared.helper", "manual.app"), setOf("recommended.app"),
            enabled = true, bypass = true)
        assertEquals(setOf("manual.app"), plan.packages)
        assertEquals(2, plan.changed)
    }

    @Test fun systemUidIsNotSelectedWithoutAnExactRule() {
        val plan = ProxyAppRecommendations.plan(installed, emptySet(), setOf("recommended.app"),
            enabled = true, bypass = false)
        assertTrue("android" !in plan.packages)
    }

    @Test fun disabledModeDoesNotApplyRememberedBypassSemantics() {
        val plan = ProxyAppRecommendations.plan(installed, emptySet(), setOf("recommended.app"),
            enabled = false, bypass = true)
        assertEquals(setOf("recommended.app", "shared.helper"), plan.packages)
        assertEquals(2, plan.changed)
    }

    @Test fun parserAcceptsCommentsAndRejectsMalformedRows() {
        val valid = (1..100).joinToString("\n") { "example.package$it" } + "\n# source"
        assertEquals(100, ProxyAppRecommendations.parse(valid).size)
        val malformed = valid + "\nnot a package"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ProxyAppRecommendations.parse(malformed)
        }
    }
}
