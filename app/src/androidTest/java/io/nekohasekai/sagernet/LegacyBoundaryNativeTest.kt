package io.nekohasekai.sagernet

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.bg.proto.*
import io.nekohasekai.sagernet.core.CoreClient
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.Socket

/** Synthetic loopback and intent resolution only; never touches production profiles or VPN. */
@RunWith(AndroidJUnit4::class)
class LegacyBoundaryNativeTest {
    @Test fun exportedLinksResolveToImporter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uuid = "00000000-0000-4000-8000-000000000001"
        val tls = Profile.Tls(serverName = "example.test")
        val profiles = listOf(
            Profile(type = "vless", server = "example.test", port = 443, vless = Profile.Vless(uuid = uuid), tls = tls),
            Profile(type = "hysteria2", server = "example.test", port = 443, hysteria2 = Profile.Hysteria2(password = "synthetic"), tls = tls),
            Profile(type = "tuic", server = "example.test", port = 443, tuic = Profile.Tuic(uuid = uuid, password = "synthetic"), tls = tls),
            Profile(type = "anytls", server = "example.test", port = 443, anytls = Profile.Password("synthetic"), tls = tls),
            Profile(type = "socks", server = "example.test", port = 1080, socks = Profile.Socks(version = "5")),
            Profile(type = "socks", server = "example.test", port = 1080, socks = Profile.Socks(version = "4a")),
        )
        for (profile in profiles) {
            val uri = Uri.parse(CoreClient.exportURI(profile))
            val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            val resolved = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue("Missing exported scheme ${uri.scheme}", resolved.any { it.activityInfo.name.endsWith("MainActivity") })
        }
        for (url in listOf("https://example.test/", "http://example.test/")) {
            assertTrue(context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName), PackageManager.MATCH_DEFAULT_ONLY).isEmpty())
        }
    }

    @Test fun runningRawListenerSurvivesRejectedStandaloneProbe() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val raw = """{"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":$port}],"outbounds":[{"type":"direct"}]}"""
        val instance = Libcore.newSingBoxInstance(raw, LocalResolverImpl)
        fun handshake() {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, socket.getInputStream().read()); assertEquals(0, socket.getInputStream().read())
            }
        }
        try {
            instance.start()
            handshake()
            val entity = ProxyEntity(type = ProxyEntity.TYPE_CONFIG, document = ProfileDocument.encode(ProfileDocument(kind = "raw_config", content = raw)))
            try {
                TestInstance(entity, "http://127.0.0.1/", 1000).doTest()
                fail("Full configuration must not start a second listener")
            } catch (expected: UnsupportedStandaloneProbe) {
                assertEquals("该配置不支持独立测速", expected.message)
            }
            handshake()
        } finally { instance.close() }
    }

    @Test fun absentNetworkReturnsNormalSkippedResult() = runBlocking {
        val result = tcpProbe(1, "synthetic", null, "example.test", 443)
        assertEquals(-1, result.status)
        assertEquals("当前无可用网络，请联网后重试", result.error)
    }
}
