package io.nekohasekai.sagernet

import io.mockk.every
import io.mockk.mockkObject
import io.nekohasekai.sagernet.ktx.Logs
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RawSubscriptionTest : RawSubscriptionCases() {
    @Before fun setup() {
        ConfigBuilderGoldenFixtureTest.setupDir()
        mockkObject(Logs)
        every { Logs.d(any<String>()) } answers {}
        every { Logs.w(any<Throwable>()) } answers {}
    }
    @After fun cleanup() = ConfigBuilderGoldenFixtureTest.tearDown()
}
