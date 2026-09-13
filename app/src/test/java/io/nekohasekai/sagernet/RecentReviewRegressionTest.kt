package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.BundledRuleSets
import io.nekohasekai.sagernet.database.RouteRuleSet
import io.nekohasekai.sagernet.utils.TcpConnectionFailure
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.net.*

class RecentReviewRegressionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun tcpFailuresKeepTheirCategoriesBeforeTranslation() {
        assertEquals(TcpConnectionFailure.REFUSED, TcpConnectionFailure.classify(ConnectException("connect failed: ECONNREFUSED (Connection refused)")))
        assertEquals(TcpConnectionFailure.UNREACHABLE, TcpConnectionFailure.classify(ConnectException("connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(TcpConnectionFailure.TIMEOUT, TcpConnectionFailure.classify(RuntimeException(SocketTimeoutException())))
        assertEquals(TcpConnectionFailure.UNREACHABLE, TcpConnectionFailure.classify(NoRouteToHostException()))
        assertEquals(TcpConnectionFailure.OTHER, TcpConnectionFailure.classify(IllegalStateException("unexpected state")))
    }

    @Test fun bundledRulesRefreshOnUpgradeAndRepairDamageWithoutDestroyingGoodCopyOnReadFailure() {
        val root = temporary.newFolder()
        val ref = RouteRuleSet.official("geosite", "cn")
        val old = "old APK bytes".toByteArray()
        val current = "new APK bytes".toByteArray()
        val file = requireNotNull(BundledRuleSets.prepare(root, ref) { old.inputStream() })
        BundledRuleSets.prepare(root, ref) { current.inputStream() }
        assertArrayEquals(current, file.readBytes())
        file.writeText("damaged")
        BundledRuleSets.prepare(root, ref) { current.inputStream() }
        assertArrayEquals(current, file.readBytes())
        assertThrows(java.io.IOException::class.java) {
            BundledRuleSets.prepare(root, ref) { throw java.io.IOException("read failed") }
        }
        assertArrayEquals(current, file.readBytes())
    }
}
