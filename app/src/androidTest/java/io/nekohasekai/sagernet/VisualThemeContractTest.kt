package io.nekohasekai.sagernet

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Xml
import android.view.LayoutInflater
import android.widget.CheckedTextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ServiceButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Isolated views: no Activity, DataStore writes, profile selection or service binding. */
@RunWith(AndroidJUnit4::class)
class VisualThemeContractTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class ViewContext(base: Context) : ContextThemeWrapper(base, Theme.getTheme()), LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    @Test fun dayAndNightContrastAndConnectionStates() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            lateinit var context: ViewContext
            lateinit var button: ServiceButton
            lateinit var progress: CircularProgressIndicator
            var buttonInitialized = false
            onMain {
                val base = instrumentation.targetContext
                val configuration = Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                context = ViewContext(base.createConfigurationContext(configuration))
                context.registry.currentState = Lifecycle.State.STARTED
            }
            try {
                onMain {
                    fun color(id: Int) = ContextCompat.getColor(context, id)
                    fun contrast(foreground: Int, background: Int, minimum: Double) {
                        val ratio = ColorUtils.calculateContrast(foreground, background)
                        assertTrue("night=$night contrast=$ratio minimum=$minimum", ratio >= minimum)
                    }
                    val surface = color(R.color.vialen_surface)
                    val resolved = context.obtainStyledAttributes(intArrayOf(
                        android.R.attr.textColorPrimary,
                        android.R.attr.textColorSecondary,
                        com.google.android.material.R.attr.colorSurface
                    ))
                    try {
                        val themedSurface = resolved.getColor(2, Color.TRANSPARENT)
                        assertEquals("Theme must expose the intended surface", surface, themedSurface)
                        contrast(resolved.getColorStateList(0)!!.defaultColor, themedSurface, 4.5)
                        contrast(resolved.getColorStateList(1)!!.defaultColor, themedSurface, 4.5)
                    } finally { resolved.recycle() }
                    // Inflate the library's real single-choice row under the actual dialog
                    // overlay. This catches Material's medium-emphasis alpha reducing contrast
                    // even when the application's raw primary-text palette is correct.
                    val dialogTheme = context.obtainStyledAttributes(intArrayOf(
                        com.google.android.material.R.attr.materialAlertDialogTheme
                    ))
                    val overlayId = try {
                        dialogTheme.getResourceId(0, 0)
                    } finally { dialogTheme.recycle() }
                    assertEquals(R.style.ThemeOverlay_MaterialComponents_MaterialAlertDialog_SagerNet, overlayId)
                    val dialogContext = ContextThemeWrapper(context, overlayId)
                    val choice = LayoutInflater.from(dialogContext).inflate(
                        com.google.android.material.R.layout.mtrl_alert_select_dialog_singlechoice,
                        null, false
                    ) as CheckedTextView
                    val dialogSurfaceAttributes = dialogContext.obtainStyledAttributes(intArrayOf(
                        com.google.android.material.R.attr.colorSurface
                    ))
                    val dialogSurface = try {
                        dialogSurfaceAttributes.getColor(0, Color.TRANSPARENT)
                    } finally { dialogSurfaceAttributes.recycle() }
                    assertEquals("Dialog surface must follow day/night", surface, dialogSurface)
                    for (checked in listOf(false, true)) {
                        choice.isChecked = checked
                        val ratio = ColorUtils.calculateContrast(choice.currentTextColor, dialogSurface)
                        assertTrue("Dialog single-choice contrast night=$night checked=$checked ratio=$ratio", ratio >= 4.5)
                    }
                    contrast(color(R.color.vialen_text_primary), surface, 4.5)
                    contrast(color(R.color.vialen_text_secondary), surface, 4.5)
                    contrast(color(R.color.vialen_on_brand), color(R.color.vialen_brand), 4.5)
                    contrast(color(R.color.vialen_accent), color(R.color.vialen_selected_background), 3.0)
                    assertEquals("Brand must retain the Logo's exact blue", Color.rgb(53, 100, 232), color(R.color.vialen_brand))
                    // Read the actual homepage FAB attributes without constructing StatsBar or MainActivity.
                    context.resources.getLayout(R.layout.layout_main).use { parser ->
                        while (parser.eventType != XmlPullParser.END_DOCUMENT &&
                            !(parser.eventType == XmlPullParser.START_TAG && parser.name == ServiceButton::class.java.name)) {
                            parser.next()
                        }
                        assertEquals(XmlPullParser.START_TAG, parser.eventType)
                        button = ServiceButton(context, Xml.asAttributeSet(parser))
                    }
                    progress = CircularProgressIndicator(context).apply { max = 1 }
                    button.initProgress(progress)
                    buttonInitialized = true
                }
                for (animate in listOf(false, true)) {
                    var previous = State.Stopped
                    for (state in listOf(State.Stopped, State.Connecting, State.Connected, State.Stopping, State.Stopped)) {
                        onMain {
                            button.changeState(state, previous, animate)
                            val connected = state == State.Connected
                            assertEquals("enabled for $state", state != State.Stopping, button.isEnabled)
                            assertEquals("checked for $state", connected, button.drawableState.contains(android.R.attr.state_checked))
                            assertEquals(context.getText(if (state == State.Connecting || connected) R.string.stop else R.string.connect), button.contentDescription)
                            val backgroundTint = requireNotNull(button.backgroundTintList) {
                                "Homepage FAB background selector was not inflated"
                            }
                            // AppCompatImageHelper loads app:tint through ImageViewCompat; on
                            // API 21+ it uses the framework ImageView tint, not FAB's support field.
                            val imageTint = requireNotNull(ImageViewCompat.getImageTintList(button)) {
                                "Homepage FAB image selector was not inflated"
                            }
                            val background = backgroundTint.getColorForState(button.drawableState, 0)
                            val foreground = imageTint.getColorForState(button.drawableState, 0)
                            assertEquals(ContextCompat.getColor(context, if (connected) R.color.vialen_brand else R.color.vialen_selected_background), background)
                            assertEquals(ContextCompat.getColor(context, if (connected) R.color.vialen_on_brand else R.color.vialen_accent), foreground)
                            assertTrue("Action glyph contrast for $state", ColorUtils.calculateContrast(foreground, background) >= 3.0)
                            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
                            try {
                                button.drawable.setBounds(0, 0, 24, 24)
                                button.drawable.draw(Canvas(bitmap))
                                assertTrue("Power glyph must render for $state", (0 until 24).any { x -> (0 until 24).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 0 } })
                            } finally { bitmap.recycle() }
                        }
                        previous = state
                        if (animate) {
                            Thread.sleep(context.resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() +
                                if (state == State.Connecting) 1200L else 100L)
                            instrumentation.waitForIdleSync()
                            if (state == State.Connecting) onMain {
                                assertTrue("Connecting must enter delayed indeterminate progress", progress.isIndeterminate)
                            }
                        }
                    }
                }
                onMain {
                    // Exercise queued reversals and cancellation without invoking the VPN.
                    button.changeState(State.Connecting, State.Stopped, true)
                    button.changeState(State.Stopped, State.Connecting, true)
                    button.changeState(State.Connecting, State.Stopped, true)
                    button.changeState(State.Stopped, State.Connecting, false)
                    assertTrue(button.isEnabled)
                    assertFalse(button.drawableState.contains(android.R.attr.state_checked))
                }
            } finally {
                onMain {
                    try {
                        if (buttonInitialized) button.changeState(State.Stopped, State.Stopped, false)
                    } finally { context.registry.currentState = Lifecycle.State.DESTROYED }
                }
            }
        }
    }
}
