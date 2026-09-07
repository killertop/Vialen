package io.nekohasekai.sagernet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.matsuri.nb4a.net.LocalResolverImpl
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import libcore.Libcore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingBoxAndroidCoreSmokeTest {

    @Before
    fun setup() {
        val app = ApplicationProvider.getApplicationContext<SagerNet>()
        SagerNet.application = app
    }

    @Test
    fun testLibcoreVersion() {
        val version = Libcore.versionBox()
        assertNotNull(version)
        assertTrue("Expected 1.14 in versionBox() but got $version", version.contains("1.14"))
    }

    @Test
    fun testShadowsocksRuntimeSmoke() {
        val ssBean = ShadowsocksBean().applyDefaultValues().apply {
            serverAddress = "127.0.0.1"
            serverPort = 8388
            method = "chacha20-ietf-poly1305"
            password = "testpassword"
        }
        val entity = ProxyEntity().apply {
            id = 1001L
            type = 2 // TYPE_SS
            putBean(ssBean)
        }
        val configResult = buildConfig(entity, false)
        assertNotNull(configResult)
        assertNotNull(configResult.config)

        val instance = Libcore.newSingBoxInstance(configResult.config, LocalResolverImpl)
        assertNotNull(instance)
        instance.close()
    }

    @Test
    fun testWireGuardEndpointRuntimeSmoke() {
        val wgBean = WireGuardBean().applyDefaultValues().apply {
            serverAddress = "127.0.0.1"
            serverPort = 51820
            peerPublicKey = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
            privateKey = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="
            localAddress = "10.0.0.2/32"
            mtu = 1420
        }
        val entity = ProxyEntity().apply {
            id = 1002L
            type = 18 // TYPE_WG
            putBean(wgBean)
        }
        val configResult = buildConfig(entity, false)
        assertNotNull(configResult)
        assertNotNull(configResult.config)

        val instance = Libcore.newSingBoxInstance(configResult.config, LocalResolverImpl)
        assertNotNull(instance)
        instance.close()
    }
}
