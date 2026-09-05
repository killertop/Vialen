package io.nekohasekai.sagernet

import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Before
import io.mockk.every
import io.mockk.mockkObject
import io.nekohasekai.sagernet.ktx.Logs

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BatchProductionTest : BatchProductionCases() {
    @Before fun quietGoLoggerOnHost() {
        mockkObject(Logs)
        every { Logs.d(any()) } answers {}
        every { Logs.i(any()) } answers {}
        every { Logs.w(any<Throwable>()) } answers {}
    }
}
