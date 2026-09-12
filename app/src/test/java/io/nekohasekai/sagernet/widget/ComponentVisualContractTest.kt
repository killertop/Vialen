package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.RustBridgeRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
class ComponentVisualContractTest {
    private fun newSwitch(): SwitchCompat = SwitchCompat(
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet)
    )

    private fun SwitchCompat.dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    @Test
    fun switchKeepsAccessibleSizeAndInsetKnobAtBothEndpoints() {
        val view = newSwitch()
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(unspecified, unspecified)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        assertTrue("Switch touch width must be at least 48dp", view.width >= view.dp(48))
        assertTrue("Switch touch height must be at least 48dp", view.height >= view.dp(48))
        assertEquals(view.dp(52), view.switchMinWidth)
        assertEquals(view.dp(52), view.trackDrawable.intrinsicWidth)
        assertEquals(view.dp(32), view.trackDrawable.intrinsicHeight)
        assertEquals(view.dp(22), view.thumbDrawable.intrinsicWidth)
        assertEquals(view.dp(32), view.thumbDrawable.intrinsicHeight)
        val padding = Rect()
        view.trackDrawable.getPadding(padding)
        assertEquals(view.dp(4), padding.left)
        assertEquals(view.dp(4), padding.right)

        // Draw only to update drawable bounds; no screenshot or pixel-color assertions.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            for (checked in listOf(false, true)) {
                view.isChecked = checked
                view.jumpDrawablesToCurrentState()
                view.draw(canvas)
                val track = view.trackDrawable.bounds
                val knob = (view.thumbDrawable as LayerDrawable).getDrawable(1).bounds
                assertEquals(view.dp(52), track.width())
                assertEquals(view.dp(32), track.height())
                assertEquals(view.dp(22), knob.width())
                assertEquals(view.dp(22), knob.height())
                assertTrue("Knob must stay inset from the left edge", knob.left >= track.left + view.dp(4))
                assertTrue("Knob must stay inset from the right edge", knob.right <= track.right - view.dp(4))
                assertEquals(view.dp(5), knob.top - track.top)
                assertEquals(view.dp(5), track.bottom - knob.bottom)
                if (checked) {
                    assertEquals(view.dp(4), track.right - knob.right)
                } else {
                    assertEquals(view.dp(4), knob.left - track.left)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun lightSwitchDistinguishesCheckedAndDisabledStates() {
        assertSwitchStateColors()
    }

    @Test
    @Config(qualifiers = "night-mdpi")
    fun darkSwitchDistinguishesCheckedAndDisabledStates() {
        assertSwitchStateColors()
    }

    private fun assertSwitchStateColors() {
        val view = newSwitch()
        val thumbTint = requireNotNull(view.thumbTintList)
        val trackTint = requireNotNull(view.trackTintList)
        fun colors(enabled: Boolean, checked: Boolean): Pair<Int, Int> {
            view.isEnabled = enabled
            view.isChecked = checked
            view.refreshDrawableState()
            return thumbTint.getColorForState(view.drawableState, thumbTint.defaultColor) to
                trackTint.getColorForState(view.drawableState, trackTint.defaultColor)
        }

        val off = colors(enabled = true, checked = false)
        val on = colors(enabled = true, checked = true)
        val disabledOff = colors(enabled = false, checked = false)
        val disabledOn = colors(enabled = false, checked = true)
        assertEquals(Color.WHITE, off.first)
        assertEquals(Color.WHITE, on.first)
        assertEquals(view.context.getColor(R.color.vialen_brand), on.second)
        assertNotEquals("Enabled track must communicate checked state", off.second, on.second)
        assertNotEquals("Disabled thumb must differ from enabled thumb", off.first, disabledOff.first)
        assertNotEquals("Disabled off track must differ from enabled off", off.second, disabledOff.second)
        assertNotEquals("Disabled on track must differ from enabled on", on.second, disabledOn.second)
        assertNotEquals("Disabled track must preserve checked-state distinction", disabledOff.second, disabledOn.second)
    }

    @Test
    fun themedDialogShowsCancelAndConfirmTextButtons() {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.Theme_SagerNet)
        controller.setup()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Component contract")
            .setMessage("Confirm this test action?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        try {
            dialog.show()
            assertTrue(dialog.isShowing)
            for ((which, label) in listOf(
                AlertDialog.BUTTON_NEGATIVE to android.R.string.cancel,
                AlertDialog.BUTTON_POSITIVE to android.R.string.ok
            )) {
                val button = requireNotNull(dialog.getButton(which))
                assertEquals(activity.getString(label), button.text.toString())
                assertEquals(View.VISIBLE, button.visibility)
                assertTrue(button.isEnabled)
                assertTrue(button.isClickable)
            }
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }
}
