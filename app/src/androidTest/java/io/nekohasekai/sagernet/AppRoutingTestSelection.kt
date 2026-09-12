package io.nekohasekai.sagernet

import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.InstalledAppAccess

/** Restrict fixtures to the target and inert test APK, never a user's unrelated app. */
internal fun isolatedAppRoutingSelection(): String {
    val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    check(testPackage != app.packageName)
    check(InstalledAppAccess.read(app).packages?.containsKey(testPackage) == true) {
        "Grant installed-app access to the isolated test target before VPN acceptance"
    }
    return "${app.packageName}\n$testPackage"
}
