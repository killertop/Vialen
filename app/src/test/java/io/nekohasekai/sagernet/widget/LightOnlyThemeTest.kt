package io.nekohasekai.sagernet.widget

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.utils.Theme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Local contexts only: no phone settings, production preferences, or VPN operations. */
@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "night-mdpi")
class LightOnlyThemeTest {
    class LightActivity : ThemedActivity()
    class LightDialogActivity : ThemedActivity() {
        override val isDialog = true
    }

    @Test fun allEntryThemesAreLightAndDisableAndroidForceDark() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
            application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        for (style in listOf(Theme.getTheme(), Theme.getDialogTheme(), R.style.Theme_Start)) {
            val context = ContextThemeWrapper(application, style)
            val attributes = context.obtainStyledAttributes(intArrayOf(
                androidx.appcompat.R.attr.isLightTheme,
                android.R.attr.forceDarkAllowed,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowBackground
            ))
            try {
                assertTrue("Theme $style must be light", attributes.getBoolean(0, false))
                assertFalse("Theme $style must disable automatic darkening", attributes.getBoolean(1, true))
                assertTrue("Theme $style must request dark status icons", attributes.getBoolean(2, false))
                assertEquals(Color.WHITE, attributes.getColor(3, Color.TRANSPARENT))
            } finally { attributes.recycle() }
            assertEquals(Color.WHITE, context.getColor(R.color.vialen_surface))
            assertEquals(Color.rgb(53, 100, 232), context.getColor(R.color.vialen_accent))
        }
    }

    @Test fun activityAndDialogIgnoreSystemAppearanceAndRecreateAsLight() {
        val previousMode = AppCompatDelegate.getDefaultNightMode()
        try {
            for (type in listOf(LightActivity::class.java, LightDialogActivity::class.java)) {
                val controller = Robolectric.buildActivity(type).setup()
                try {
                    fun verify() {
                        val activity = controller.get()
                        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
                        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                        assertEquals(Color.WHITE, activity.getColor(R.color.vialen_surface))
                        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        assertTrue(bars.isAppearanceLightStatusBars)
                        assertTrue(bars.isAppearanceLightNavigationBars)
                    }
                    verify()
                    controller.recreate()
                    verify()
                } finally { controller.pause().stop().destroy() }
            }
        } finally { AppCompatDelegate.setDefaultNightMode(previousMode) }
    }
}
