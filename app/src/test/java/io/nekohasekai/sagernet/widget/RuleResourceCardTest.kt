package io.nekohasekai.sagernet.widget

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-520dpi")
class RuleResourceCardTest {
    @Test fun longSourceCannotClipTheSeparateUpdateTimestampOrFailure() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet)
        val parent = FrameLayout(context)
        val card = LayoutAssetItemBinding.inflate(LayoutInflater.from(context), parent, true)
        card.assetName.text = "geosite-category-ads-all"
        card.assetSource.text = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs"
        card.assetStatus.text = context.getString(R.string.route_set_last_checked, "2026-09-12 01:52:01")
        card.assetError.visibility = View.VISIBLE
        card.assetError.text = context.getString(R.string.route_set_update_failed, "HTTP 503")
        parent.measure(View.MeasureSpec.makeMeasureSpec(1220, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        for (text in listOf(card.assetStatus, card.assetError)) {
            assertTrue(text.height >= text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
        }
        assertTrue(card.assetStatus.text.contains("2026-09-12 01:52:01"))
        assertEquals(View.VISIBLE, card.rulesUpdate.visibility)
    }
}
