package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.utils.AppRoutingConfig
import io.nekohasekai.sagernet.utils.AppRoutingConfig.Problem.*
import org.junit.Assert.*
import org.junit.Test

class AppRoutingConfigTest {
    private val own = "com.vialen.app"
    private val installed = setOf("app.one", "app.two")

    @Test fun disabledCanBeSavedAfterRevocationWithoutDroppingSelection() {
        val config = AppRoutingConfig(false, false, setOf("missing"))
        assertNull(config.validate(null, own))
        assertEquals(setOf("missing"), config.packages)
    }
    @Test fun bothEnabledModesRequireAccess() {
        for (bypass in listOf(true, false))
            assertEquals(ACCESS_UNAVAILABLE, AppRoutingConfig(true, bypass, installed).validate(null, own))
    }
    @Test fun proxyRejectsEmptyAndOwnPackageOnly() {
        for (selection in listOf(emptySet(), setOf(own)))
            assertEquals(EMPTY_SELECTION, AppRoutingConfig(true, false, selection).validate(installed, own))
    }
    @Test fun emptyBypassIsWellDefined() {
        assertNull(AppRoutingConfig(true, true).validate(installed, own))
    }
    @Test fun validSelectionAcceptedInBothModes() {
        for (bypass in listOf(true, false))
            assertNull(AppRoutingConfig(true, bypass, setOf("app.one")).validate(installed, own))
    }
    @Test fun unavailableSelectedAppsNeverSilentlyDiscarded() {
        for (bypass in listOf(true, false)) {
            val config = AppRoutingConfig(true, bypass, setOf("missing", "app.one"))
            assertEquals(MISSING_APPS, config.validate(installed, own))
            assertTrue("missing" in config.packages)
        }
    }
    @Test fun changingDraftDoesNotMutateOriginal() {
        val original = AppRoutingConfig(true, true, installed)
        val draft = original.copy(bypass = false, packages = original.packages - "app.one")
        assertEquals(installed, original.packages)
        assertTrue(original.bypass)
        assertEquals(setOf("app.two"), draft.packages)
    }
    @Test fun importTrimsDeduplicatesAndDropsBlankLines() {
        assertEquals(installed, AppRoutingConfig.parsePackages(" app.one \r\n\napp.two\napp.one\n"))
    }
}
