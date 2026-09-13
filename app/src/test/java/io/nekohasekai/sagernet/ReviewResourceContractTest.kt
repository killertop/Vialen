package io.nekohasekai.sagernet

import android.app.Activity
import android.app.Application
import android.content.DialogInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.xmlpull.v1.XmlPullParser

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReviewResourceContractTest {
    private fun includes(resource: Int): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        RuntimeEnvironment.getApplication().resources.getXml(resource).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "include") {
                    result.add((0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    })
                }
                parser.next()
            }
        }
        return result
    }

    @Test @Config(sdk = [24]) fun oldBackupTransportsNeverReceiveCredentialDatabases() {
        assertEquals(listOf(mapOf("domain" to "file", "path" to "rule-sets/")), includes(R.xml.backup_descriptor))
    }

    @Test @Config(sdk = [28]) fun legacyDatabaseBackupRequiresEncryptionAndUsesCurrentNames() {
        val databases = includes(R.xml.backup_descriptor).filter { it["domain"] == "database" }
        assertEquals(setOf(Key.DB_PROFILE, Key.DB_PUBLIC), databases.map { it["path"] }.toSet())
        assertTrue(databases.all { it["requireFlags"] == "clientSideEncryption" })
    }

    @Test fun bypassPrivateRoutesCoverPreviouslyMissingPublicDnsRanges() {
        fun ip(value: String): Long = value.split('.').fold(0L) { n, part -> (n shl 8) or part.toLong() }
        val routes = RuntimeEnvironment.getApplication().resources.getStringArray(R.array.bypass_private_route)
        fun covered(address: String) = routes.any { cidr ->
            val (network, prefix) = cidr.split('/')
            val mask = (0xffffffffL shl (32 - prefix.toInt())) and 0xffffffffL
            (ip(address) and mask) == (ip(network) and mask)
        }
        for (address in listOf("112.124.47.0", "112.124.47.255", "114.114.114.0", "114.114.114.255", "8.8.8.8")) {
            assertTrue(address, covered(address))
        }
        for (address in listOf("10.1.2.3", "172.16.0.1", "192.168.1.1")) assertFalse(address, covered(address))
    }

    @Test fun shortcutRequiresAnExplicitPositiveGesture() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var operations = 0
        controller.get().confirmShortcut(R.string.quick_toggle, "Switch to synthetic profile") { operations++ }
        assertEquals(0, operations)
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, operations)
        controller.destroy()
        val positive = Robolectric.buildActivity(Activity::class.java).setup()
        positive.get().confirmShortcut(R.string.quick_toggle) { operations++ }
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, operations)
        positive.destroy()
    }
}
