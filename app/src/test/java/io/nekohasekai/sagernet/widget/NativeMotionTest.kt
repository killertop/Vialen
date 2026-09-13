package io.nekohasekai.sagernet.widget

import android.app.Activity
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.TextView
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NativeMotionTest {
    @Test fun rapidStateChangesNeverDeferTextAndDetachSettlesPresentation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val text = TextView(activity)
        root.addView(text)
        activity.setContentView(root)
        try {
            NativeMotion.text(text, "Testing")
            assertEquals("Testing", text.text.toString())
            NativeMotion.text(text, "Failed")
            NativeMotion.text(text, "Disconnected")
            assertEquals("Disconnected", text.text.toString())
            root.removeView(text)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertEquals("Disconnected", text.text.toString())
            assertEquals(1f, text.alpha)
            assertEquals(0f, text.translationY)
            root.addView(text)
            NativeMotion.text(text, "Ready")
            assertEquals("Ready", text.text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun disabledMotionImmediatelyProducesFinalStateAndSameTextDoesNotRestart() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val text = TextView(activity)
        activity.setContentView(text)
        try {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            assertFalse(NativeMotion.enabled(activity))
            NativeMotion.text(text, "Available")
            assertEquals("Available", text.text.toString())
            assertEquals(1f, text.alpha)
            assertEquals(0f, text.translationY)
            NativeMotion.text(text, "Available")
            assertEquals(1f, text.alpha)
            assertTrue(text.isEnabled)
        } finally {
            Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            controller.pause().stop().destroy()
        }
    }
}
