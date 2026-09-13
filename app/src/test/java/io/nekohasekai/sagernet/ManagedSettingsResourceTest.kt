package io.nekohasekai.sagernet

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.utils.RuntimeDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Resource contract only; does not substitute for physical interaction/layout acceptance. */
@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagedSettingsResourceTest {
    private fun categories(): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var category: String? = null
        ApplicationProvider.getApplicationContext<Application>().resources
            .getXml(R.xml.global_preferences).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        val key = parser.getAttributeValue(
                            "http://schemas.android.com/apk/res-auto", "key")
                        if (parser.name == "PreferenceCategory") {
                            category = requireNotNull(key)
                            result[category!!] = mutableListOf()
                        } else if (parser.depth == 3 && key != null) {
                            result.getValue(requireNotNull(category)).add(key)
                        }
                    } else if (parser.eventType == XmlPullParser.END_TAG &&
                        parser.name == "PreferenceCategory") {
                        category = null
                    }
                    parser.next()
                }
            }
        return result
    }

    private fun keys(): Set<String> {
        val result = mutableSetOf<String>()
        ApplicationProvider.getApplicationContext<Application>().resources
            .getXml(R.xml.global_preferences).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        assertNull("Settings must not require expansion", parser.getAttributeValue(
                            "http://schemas.android.com/apk/res-auto", "initialExpandedChildrenCount"))
                        parser.getAttributeValue("http://schemas.android.com/apk/res-auto", "key")?.let {
                            assertTrue("Duplicate setting key: $it", result.add(it))
                        }
                    }
                    parser.next()
                }
            }
        return result
    }

    @Test fun retiredKnobsAndDiagnosticEntriesStayHiddenWithManagedPolicyRetained() {
        val keys = keys()
        for (removed in listOf("speedInterval", "appTLSVersion", "logLevel", "uiLogBuffer", "nightTheme",
            "showBottomBar", "showGroupInNotification", "uiDetailedDiagnostics", "uiManagedSettings")) {
            assertFalse(removed, removed in keys)
        }
        assertEquals(256, RuntimeDiagnostics.LOG_CAPACITY_KIB)
        assertEquals(2, RuntimeDiagnostics.NORMAL_LOG_LEVEL)
    }

    @Test fun userIntentAndUnverifiedCompatibilityControlsRemain() {
        val keys = keys()
        assertFalse("Per-app routing must have only one editor entry", "proxyApps" in keys)
        for (retained in listOf("isAutoConnect", "uiEditApps", "remoteDns", "directDns",
            "enableDnsRouting", "enableFakeDns", "bypassLan", "bypassLanInCore", "allowAccess",
            "profileTrafficStatistics", "ipv6Mode",
            "tunImplementation", "acquireWakeLock", "networkChangeResetConnections", "wakeResetConnections")) {
            assertTrue("Missing retained setting: $retained", retained in keys)
        }
    }

    @Test fun settingsRemainGroupedByUserPurpose() {
        assertEquals(linkedMapOf(
            "uiDisplayStatistics" to listOf(
                "alwaysShowAddress", "profileTrafficStatistics"),
            "uiTrafficRouting" to listOf(
                "uiEditApps", "bypassLan", "bypassLanInCore", "trafficSniffing",
                "resolveDestination", "ipv6Mode"),
            "uiConnectionRuntime" to listOf(
                "isAutoConnect", "serviceMode", "meteredNetwork",
                "networkChangeResetConnections", "wakeResetConnections", "acquireWakeLock",
                "tunImplementation"),
            "uiDnsResolution" to listOf(
                "remoteDns", "domain_strategy_for_remote", "directDns",
                "domain_strategy_for_direct", "domain_strategy_for_server", "enableDnsRouting",
                "enableFakeDns"),
            "uiLocalProxy" to listOf("mixedPort", "appendHttpProxy", "allowAccess"),
            "uiSecurity" to listOf("globalAllowInsecure", "allowInsecureOnRequest"),
            "uiDiagnosticsMaintenance" to listOf("connectionTestURL"),
        ), categories())
    }
}
