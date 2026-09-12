package io.nekohasekai.sagernet.widget

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-mdpi")
class HomeImportHintTest {
    private fun context() = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet
    )

    @Test
    fun homeHasNoRedundantImportBannerAboveTheGroups() {
        val context = context()
        val root = LayoutInflater.from(context).inflate(R.layout.layout_group_list, null) as LinearLayout
        assertEquals(3, root.childCount)
        assertEquals(R.id.appbar, root.getChildAt(0).id)
        assertEquals(R.id.group_tab, root.getChildAt(1).id)
        assertEquals(R.id.group_pager, root.getChildAt(2).id)
        assertEquals(View.GONE, root.findViewById<View>(R.id.group_tab).visibility)
    }

    @Test
    fun englishGuidanceNamesBothImportTypesAndExistingActions() {
        val context = context()
        for (resource in listOf(R.string.ui_empty_body, R.string.ui_import_hint)) {
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
        for (resource in listOf(R.string.ui_empty_body, R.string.ui_import_hint)) {
            val text = context.getString(resource)
            assertTrue(text.contains("机场订阅"))
            assertTrue(text.contains("单节点链接"))
        }
        val hint = context.getString(R.string.ui_import_hint)
        assertTrue(hint.contains(context.getString(R.string.ui_add_subscription)))
        assertTrue(hint.contains(context.getString(R.string.action_import)))
    }
}
