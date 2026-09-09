package io.nekohasekai.sagernet

import android.app.Application
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.utils.toBytesString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocaleFormattingContractTest {
    @Test fun transportNormalizationIsIndependentOfTurkishLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val bean = VMessBean().apply { type = "QUIC"; initializeDefaultValues() }
            assertEquals("quic", bean.type)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun displayedTrafficRetainsUserDecimalSeparator() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1,50 KiB", 1536L.toBytesString())
        } finally { Locale.setDefault(previous) }
    }
}
