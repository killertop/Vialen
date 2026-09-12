package io.nekohasekai.sagernet

import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypographyContractNativeTest {
    private fun verify(night: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val base = instrumentation.targetContext
                // Local view context only: never change the phone's display or app preferences.
                val config = Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                }
                TypographyContract.verify(ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_SagerNet))
            } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }
    @Test fun lightTypographyRoles() = verify(Configuration.UI_MODE_NIGHT_NO)
    @Test fun darkTypographyRoles() = verify(Configuration.UI_MODE_NIGHT_YES)
}
