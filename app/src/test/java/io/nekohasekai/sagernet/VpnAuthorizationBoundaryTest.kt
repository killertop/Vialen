package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import io.mockk.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.ktx.Logs
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VpnAuthorizationBoundaryTest {
    @Before fun setup() {
        SagerNet.application = mockk<SagerNet>(relaxed = true)
        mockkObject(DataStore)
        mockkStatic(VpnService::class)
        mockkStatic(ContextCompat::class)
        every { ContextCompat.startForegroundService(any(), any()) } just Runs
        mockkObject(Logs)
        every { Logs.e(any<String>()) } just Runs
    }
    @After fun cleanup() = unmockkAll()

    @Test fun proxyModeDoesNotAskForVpnConsent() {
        every { DataStore.serviceMode } returns Key.MODE_PROXY
        SagerNet.startService()
        verify(exactly = 0) { VpnService.prepare(any()) }
        verify(exactly = 0) { SagerNet.application.startActivity(any()) }
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), match {
            it.component!!.className == "io.nekohasekai.sagernet.bg.ProxyService"
        }) }
    }
    @Test fun unsupportedModeRetainsFailureWithoutAskingForVpnConsent() {
        every { DataStore.serviceMode } returns "unsupported"
        assertThrows(UnknownError::class.java) { SagerNet.startService() }
        verify(exactly = 0) { VpnService.prepare(any()) }
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
        verify(exactly = 0) { SagerNet.application.startActivity(any()) }
    }
    @Test fun preparedVpnStartsExactlyOnceWithoutConsentActivity() {
        every { DataStore.serviceMode } returns Key.MODE_VPN
        every { VpnService.prepare(any()) } returns null
        SagerNet.startService()
        verify(exactly = 1) { VpnService.prepare(any()) }
        verify(exactly = 0) { SagerNet.application.startActivity(any()) }
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), match {
            it.component!!.className == "io.nekohasekai.sagernet.bg.VpnService"
        }) }
    }
    @Test fun deniedVpnThenCancelDoesNotStartService() {
        every { DataStore.serviceMode } returns Key.MODE_VPN
        every { VpnService.prepare(any()) } returns Intent("consent")
        SagerNet.startService()
        assertTrue(VpnRequestActivity.StartService().parseResult(Activity.RESULT_CANCELED, null))
        verify(exactly = 1) { SagerNet.application.startActivity(match {
            it.component!!.className == VpnRequestActivity::class.java.name
        }) }
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
    }
    @Test fun firstConsentContractResumesExactlyOnceAfterApproval() {
        every { DataStore.serviceMode } returns Key.MODE_VPN
        every { VpnService.prepare(any()) } returns Intent("consent")
        SagerNet.startService()
        val contract = VpnRequestActivity.StartService()
        assertNull(contract.getSynchronousResult(SagerNet.application, null))
        assertEquals("consent", contract.createIntent(SagerNet.application, null).action)
        verify(exactly = 0) { ContextCompat.startForegroundService(any(), any()) }
        every { VpnService.prepare(any()) } returns null
        assertFalse(contract.parseResult(Activity.RESULT_OK, null))
        verify(exactly = 1) { SagerNet.application.startActivity(any()) }
        verify(exactly = 1) { ContextCompat.startForegroundService(any(), any()) }
    }
}
