package io.nekohasekai.sagernet

import io.mockk.every
import io.mockk.mockkObject
import io.nekohasekai.sagernet.ktx.Logs
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProtocolAcceptanceTest : ProtocolAcceptanceCases() {
    @Before fun setup() {
        mockkObject(Logs)
        every { Logs.i(any()) } answers {}
        every { Logs.w(any<Throwable>()) } answers {}
    }
}
