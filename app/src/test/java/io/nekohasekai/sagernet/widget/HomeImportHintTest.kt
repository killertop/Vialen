package io.nekohasekai.sagernet.widget

import android.app.Application
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.RustBridgeRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-mdpi")
class HomeImportHintTest {
    private fun context() = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet
    )

    @Test
    fun hintUsesSecondaryTypographyAboveTheGroupsAndAllowsWrapping() {
        val context = context()
        val root = LayoutInflater.from(context).inflate(R.layout.layout_group_list, null) as LinearLayout
        val hint = root.findViewById<TextView>(R.id.home_import_hint)
        assertEquals(context.getString(R.string.ui_home_import_hint), hint.text.toString())
        val secondarySize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
        )
        assertEquals(secondarySize, hint.textSize, 0.01f)
        assertEquals(context.getColor(R.color.vialen_text_secondary), hint.currentTextColor)
        assertTrue(root.indexOfChild(hint) < root.indexOfChild(root.findViewById(R.id.group_tab)))
        assertTrue(hint.maxLines > 1)
        assertNull(hint.ellipsize)
        assertFalse(hint.isClickable)
        // The fragment explicitly shows it only for home, avoiding a flash in selection mode.
        assertEquals(View.GONE, hint.visibility)
    }

    @Test
    fun englishGuidanceNamesBothImportTypesAndExistingActions() {
        val context = context()
        for (resource in listOf(R.string.ui_home_import_hint, R.string.ui_empty_body, R.string.ui_import_hint)) {
            val text = context.getString(resource)
            assertTrue(text.contains("provider subscription"))
            assertTrue(text.contains("individual node link"))
        }
        val hint = context.getString(R.string.ui_import_hint)
        assertTrue(hint.contains(context.getString(R.string.ui_add_subscription)))
        assertTrue(hint.contains(context.getString(R.string.action_import)))
    }

    @Test
    @Config(qualifiers = "zh-rCN-mdpi")
    fun chineseGuidanceNamesBothImportTypesAndExistingActions() {
        val context = context()
        assertEquals("支持机场订阅和单节点链接导入", context.getString(R.string.ui_home_import_hint))
        for (resource in listOf(R.string.ui_home_import_hint, R.string.ui_empty_body, R.string.ui_import_hint)) {
            val text = context.getString(resource)
            assertTrue(text.contains("机场订阅"))
            assertTrue(text.contains("单节点链接"))
        }
        val hint = context.getString(R.string.ui_import_hint)
        assertTrue(hint.contains(context.getString(R.string.ui_add_subscription)))
        assertTrue(hint.contains(context.getString(R.string.action_import)))
    }
}
