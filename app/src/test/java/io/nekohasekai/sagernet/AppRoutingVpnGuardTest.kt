package io.nekohasekai.sagernet

import io.mockk.*
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.AppRoutingStore
import io.nekohasekai.sagernet.utils.AppRoutingConfig
import io.nekohasekai.sagernet.utils.InstalledAppAccess
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Real startProcesses boundary, without establishing TUN or opening a network connection. */
@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppRoutingVpnGuardTest {
    @Before fun setup() { mockkObject(AppRoutingStore, InstalledAppAccess) }
    @After fun cleanup() { unmockkObject(AppRoutingStore, InstalledAppAccess) }
    @Test fun deniedAccessStopsBeforeNetworkLifecycleOrNativeStartup() = runBlocking {
        every { AppRoutingStore.read() } returns AppRoutingConfig(true, false, setOf("app.one"))
        every { InstalledAppAccess.read(any()) } returns InstalledAppAccess.Snapshot(denied = true)
        val service = Robolectric.buildService(VpnService::class.java).get()
        val error = runCatching { service.startProcesses() }.exceptionOrNull()
        assertTrue(error is BaseService.ExpectedException)
        assertNull(service.conn)
        verify(exactly = 1) { InstalledAppAccess.read(any()) }
    }
    @Test fun emptyProxySelectionStopsBeforeTunEstablishment() = runBlocking {
        every { AppRoutingStore.read() } returns AppRoutingConfig(true, false)
        every { InstalledAppAccess.read(any()) } returns InstalledAppAccess.Snapshot(emptyMap())
        val service = Robolectric.buildService(VpnService::class.java).get()
        val error = runCatching { service.startProcesses() }.exceptionOrNull()
        assertTrue(error is BaseService.ExpectedException)
        assertNull(service.conn)
    }
}
