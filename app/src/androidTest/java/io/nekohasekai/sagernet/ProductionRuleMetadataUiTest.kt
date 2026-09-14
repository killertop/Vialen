package io.nekohasekai.sagernet

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Opt-in external UI acceptance: starts in an unsaved production rule-set dialog. */
class ProductionRuleMetadataUiTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) {
            result += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun click(text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var found = nodes().firstOrNull { it.text?.toString() == text }
        while (found == null && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(50)
            found = nodes().firstOrNull { it.text?.toString() == text }
        }
        var node = requireNotNull(found) { "UI text not found: $text" }
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        automation.waitForIdle(300, 5000)
    }
    private fun fields(): List<AccessibilityNodeInfo> {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var found = nodes().filter { it.className == "android.widget.EditText" }
        while (found.size < 2 && android.os.SystemClock.uptimeMillis() < deadline) {
            android.os.SystemClock.sleep(50)
            found = nodes().filter { it.className == "android.widget.EditText" }
        }
        assertEquals("Expected metadata editor", 2, found.size)
        return found
    }
    private fun fill(value: String) {
        val field = fields()[1]
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    @Test fun rejectInvalidMetadataAndAcceptMatchingJson() {
        // This test must never navigate into or modify an existing user rule.
        org.junit.Assume.assumeTrue("Requires an explicit unsaved production draft",
            InstrumentationRegistry.getArguments().getString("productionDraftAcceptance") == "1")
        val initialFields = fields()
        assertEquals("com.vialen.app", initialFields[0].packageName.toString())
        assertEquals("QAValidation", initialFields[0].text.toString())
        if (nodes().any { it.text?.toString() == "JSON (.json)" }) {
            click("JSON (.json)"); click("SRS (.srs)")
        }
        for (url in listOf("https://example.invalid/rules.json", "https://example.invalid:65536/rules.srs",
            "https://example.invalid:0/rules.srs", "https://example.invalid")) {
            fill(url)
            click("保存")
            val field = fields()[1]
            assertEquals(url, field.text.toString())
            assertFalse("Missing validation error for $url", field.error.isNullOrBlank())
        }
        if (InstrumentationRegistry.getArguments().getString("validFormat") == "binary") {
            fill("https://example.invalid/rules.srs")
        } else {
            fill("https://example.invalid/rules.json")
            click("SRS (.srs)")
            click("JSON (.json)")
        }
        click("保存")
        assertFalse(nodes().any { it.className == "android.widget.EditText" })
    }
}
