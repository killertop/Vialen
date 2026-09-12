package io.nekohasekai.sagernet

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.*
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.InstalledAppAccess
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class InstalledAppAccessTest {
    private lateinit var context: Context
    private lateinit var pm: PackageManager
    @Before fun setup() {
        context = mockk(); pm = mockk()
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.vialen.app"
        every { context.checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        every { pm.getPermissionInfo(any<String>(), any<Int>()) } throws PackageManager.NameNotFoundException()
        mockkObject(Logs)
        every { Logs.w(any<Throwable>()) } just Runs
    }
    @After fun cleanup() { unmockkObject(Logs) }
    private fun pkg(name: String) = PackageInfo().apply {
        packageName = name
        requestedPermissions = arrayOf(Manifest.permission.INTERNET)
        applicationInfo = ApplicationInfo().apply { packageName = name; flags = ApplicationInfo.FLAG_INSTALLED }
    }
    @Test fun deniedAuthorizationDoesNotQueryOrTrustAPartialList() {
        every { context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) } returns PackageManager.PERMISSION_DENIED
        val snapshot = InstalledAppAccess.read(context)
        assertTrue(snapshot.denied)
        assertNull(snapshot.packages)
        verify(exactly = 0) { pm.getInstalledPackages(any<Int>()) }
    }
    @Test fun freshQueryExcludesSelfAndRetainedUninstalledPackages() {
        val uninstalled = pkg("old.app").apply { applicationInfo!!.flags = 0 }
        every { pm.getInstalledPackages(any<Int>()) } returns listOf(pkg("com.vialen.app"), pkg("app.one"), uninstalled)
        assertEquals(setOf("app.one"), InstalledAppAccess.read(context).packages?.keys)
    }
    @Test fun revokedDuringQueryFailsClosed() {
        every { pm.getInstalledPackages(any<Int>()) } answers {
            every { context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) } returns PackageManager.PERMISSION_DENIED
            listOf(pkg("app.one"))
        }
        assertTrue(InstalledAppAccess.read(context).denied)
    }
    @Test fun queryFailureAndEmptyListAreUnavailableNotValidConfiguration() {
        every { pm.getInstalledPackages(any<Int>()) } throws IllegalStateException("fixture")
        assertNull(InstalledAppAccess.read(context).packages)
        every { pm.getInstalledPackages(any<Int>()) } returns emptyList()
        assertNull(InstalledAppAccess.read(context).packages)
    }
    @Test fun securityExceptionBecomesPermissionGuidance() {
        every { pm.getInstalledPackages(any<Int>()) } throws SecurityException("fixture")
        assertTrue(InstalledAppAccess.read(context).denied)
    }
}
