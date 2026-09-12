package io.nekohasekai.sagernet.widget

import android.app.Application
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import io.nekohasekai.sagernet.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Layout/state contracts only; physical Release screenshots remain the visual acceptance gate. */
@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
class PearlSurfaceContractTest {
    private fun context() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet)

    @Test fun listCardsShareTheSameSurfaceWithoutChangingTheirMargins() {
        val context = context()
        val parent = android.widget.FrameLayout(context)
        for (layout in listOf(R.layout.layout_profile, R.layout.layout_group_item, R.layout.layout_route_item, R.layout.layout_empty_route)) {
            val card = LayoutInflater.from(context).inflate(layout, parent, false) as MaterialCardView
            assertEquals(Color.WHITE, card.cardBackgroundColor.defaultColor)
            assertEquals(context.getColor(R.color.vialen_pearl_outline), card.strokeColorStateList?.defaultColor)
            assertEquals(1, card.strokeWidth)
            assertEquals(1f, card.cardElevation, 0.01f)
            assertEquals(if (layout == R.layout.layout_profile) 12f else 16f, card.radius, 0.01f)
            val params = card.layoutParams as ViewGroup.MarginLayoutParams
            assertEquals(4, params.leftMargin)
            assertEquals(4, params.rightMargin)
            assertEquals(4, params.topMargin)
            assertEquals(4, params.bottomMargin)
        }
    }

    @Test fun nodeControlsRetainThreeSeparate48dpTargetsAndTextRoles() {
        val context = context()
        val card = LayoutInflater.from(context).inflate(R.layout.layout_profile, null) as MaterialCardView
        card.findViewById<View>(R.id.remove).visibility = View.VISIBLE
        card.findViewById<TextView>(R.id.profile_name).text = "CF官方优选6"
        card.measure(View.MeasureSpec.makeMeasureSpec(344, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        for (id in listOf(R.id.edit, R.id.share, R.id.remove)) {
            val control = card.findViewById<View>(id)
            assertTrue(control.measuredWidth >= 48)
            assertTrue(control.measuredHeight >= 48)
            assertNotNull(control.contentDescription)
        }
        assertEquals(16f, card.findViewById<TextView>(R.id.profile_name).textSize, 0.01f)
        assertEquals(14f, card.findViewById<TextView>(R.id.profile_type).textSize, 0.01f)
        assertEquals(14f, card.findViewById<TextView>(R.id.profile_status).textSize, 0.01f)
        assertEquals(4, card.findViewById<View>(R.id.selected_view).layoutParams.width)
    }

    @Test fun updateTimeAndUpdateActionRemainInTheGroupCard() {
        val context = context()
        val card = LayoutInflater.from(context).inflate(R.layout.layout_group_item, null)
        val status = card.findViewById<TextView>(R.id.group_status)
        assertEquals(View.VISIBLE, status.visibility)
        assertEquals(14f, status.textSize, 0.01f)
        val update = card.findViewById<TextView>(R.id.group_update)
        assertEquals(context.getString(R.string.group_update), update.text.toString())
        assertEquals(context.getColor(R.color.vialen_accent), update.currentTextColor)
    }

    @Test fun powerButtonStillDistinguishesConnectedAndDisconnected() {
        val context = context()
        val background = context.getColorStateList(R.color.vialen_service_background)
        val foreground = context.getColorStateList(R.color.vialen_service_foreground)
        val checked = intArrayOf(android.R.attr.state_checked)
        assertEquals(Color.WHITE, background.defaultColor)
        assertEquals(context.getColor(R.color.vialen_brand), background.getColorForState(checked, 0))
        assertEquals(context.getColor(R.color.vialen_brand), foreground.defaultColor)
        assertEquals(Color.WHITE, foreground.getColorForState(checked, 0))
    }

    @Test fun settingsCategoryHasNoOpaqueBandAndKeepsItsTextSize() {
        val title = LayoutInflater.from(context()).inflate(R.layout.preference_settings_category, null) as TextView
        assertEquals(Color.TRANSPARENT, (title.background as ColorDrawable).color)
        assertEquals(14f, title.textSize, 0.01f)
        assertEquals(44, title.minimumHeight)
    }

    @Test fun visibleScrollEdgesDoNotBecomeFalseSectionCorners() {
        val categories = setOf(0, 4, 10)
        fun edges(first: Int, last: Int) = PreferenceSurfaceDecoration.groupEdges(first, last, 14) { it in categories }
        assertEquals(true to true, edges(1, 3))
        assertEquals(false to true, edges(2, 3))
        assertEquals(true to false, edges(5, 7))
        assertEquals(false to false, edges(6, 8))
        assertEquals(true to true, edges(11, 13))
    }

    @Test fun decorationNeverAddsInsetsOrChangesTouchGeometry() {
        val context = context()
        val offsets = Rect(10, 20, 30, 40)
        PreferenceSurfaceDecoration(context).getItemOffsets(offsets, View(context), RecyclerView(context), RecyclerView.State())
        assertEquals(Rect(), offsets)
    }

    @Test fun foregroundColorsKeepReadableContrastOnBothCardStates() {
        val context = context()
        for (background in listOf(R.color.vialen_surface, R.color.vialen_pearl_selected, R.color.vialen_pearl_edge)) {
            for (foreground in listOf(R.color.vialen_text_primary, R.color.vialen_text_secondary, R.color.vialen_error)) {
                assertTrue("Text contrast must be at least 4.5:1", ColorUtils.calculateContrast(context.getColor(foreground), context.getColor(background)) >= 4.5)
            }
        }
    }
}
