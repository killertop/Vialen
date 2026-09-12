package io.nekohasekai.sagernet.widget

import android.app.Application
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import io.nekohasekai.sagernet.TypographyContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "mdpi")
class TypographyContractTest {
    private fun verify() = TypographyContract.verify(ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), R.style.Theme_SagerNet))

    @Test fun lightTypographyRoles() = verify()
    @Test @Config(qualifiers = "night-mdpi") fun lightTypographyIgnoresSystemAppearance() = verify()
}
