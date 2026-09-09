package io.nekohasekai.sagernet.widget

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.behavior.HideBottomViewOnScrollBehavior
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StatsBarBehaviorTest {
    @Test fun businessHideOverridesTouchExplorationButScrollHideDoesNot() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet)
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).setTouchExplorationEnabled(true)
        var allowed = true
        val behavior = StatsBar.YourBehavior { allowed }
        val bar = BottomAppBar(context)
        // onLayoutChild normally obtains this system service. Injecting the same service avoids
        // unrelated Coordinator/FAB layout while exercising Material's real hide implementation.
        HideBottomViewOnScrollBehavior::class.java.getDeclaredField("accessibilityManager")
            .apply { isAccessible = true }.set(behavior, manager)
        try {
            behavior.slideDown(bar, false)
            assertTrue("Touch exploration keeps scroll-hidden content available", behavior.isScrolledUp)
            assertEquals(View.VISIBLE, bar.visibility)

            allowed = false
            behavior.slideDown(bar, false)
            assertTrue("Page/state policy must hide even with TalkBack", behavior.isScrolledDown)
            assertEquals(View.INVISIBLE, bar.visibility)
            assertTrue("Scroll accessibility policy is restored", behavior.isDisabledOnTouchExploration)
            behavior.slideUp(bar, false)
            assertTrue("A late show must respect page policy", behavior.isScrolledDown)

            allowed = true
            behavior.slideUp(bar, false)
            assertEquals(View.VISIBLE, bar.visibility)
            behavior.slideDown(bar) // inherited one-argument overload must also route through the gate
            assertTrue(behavior.isScrolledUp)
        } finally { shadowOf(manager).setTouchExplorationEnabled(false) }
    }
}
