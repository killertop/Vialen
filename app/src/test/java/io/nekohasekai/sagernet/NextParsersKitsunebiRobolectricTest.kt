package io.nekohasekai.sagernet

import io.mockk.every
import io.mockk.mockkObject
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.oracle.parseV2Ray
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getStr
import io.nekohasekai.sagernet.rust.RustBridge
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.internal.bytecode.InstrumentationConfiguration
import java.util.Base64 as JavaBase64

// JNI libraries have one owning ClassLoader per JVM. Share the pure JVM bridge
// and its result types with ordinary JUnit tests while retaining Android shadows.
class RustBridgeRobolectricTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
        InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
            .doNotAcquirePackage("io.nekohasekai.sagernet.rust.")
            .build()
}

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NextParsersKitsunebiRobolectricTest {

    @Before
    fun setup() {
        mockkObject(Logs)
        every { Logs.d(any()) } answers {}
        every { Logs.d(any(), any()) } answers {}
        every { Logs.i(any()) } answers {}
        every { Logs.i(any(), any()) } answers {}
        every { Logs.w(any<String>()) } answers {}
        every { Logs.w(any<Throwable>()) } answers {}
        every { Logs.w(any(), any()) } answers {}
        every { Logs.e(any<String>()) } answers {}
        every { Logs.e(any<Throwable>()) } answers {}
        every { Logs.e(any(), any()) } answers {}
    }

    // ==========================================
    // LEGACY VMESS KITSUNEBI DIFFERENTIAL (ROBOLECTRIC)
    // ==========================================
    @Test
    fun testLegacyInvalidFieldsProductionOutcome() {
        val encoded = JavaBase64.getEncoder().encodeToString(
            "auto:5af5d0ec-6ea0-3c43-93db-ca3008503bdb@example.com:443".toByteArray()
        )
        for (query in listOf("alterId=abc", "alterId=2147483648", "obfs=websocket&obfsParam=%7Bbroken")) {
            val uri = "vmess://$encoded?$query"
            val kotlin = runCatching { parseV2Ray(uri) }
            val rust = RustBridge.parseProxy(uri)
            assertEquals("success parity: $query", kotlin.isSuccess, rust.status == "SUCCESS")
            if (kotlin.isSuccess) {
                assertEquals("fallback endpoint: $query", kotlin.getOrThrow().serverAddress, rust.server)
            }
        }
    }

    @Test
    fun testLegacyQueryFirstOccurrenceParity() {
        val encoded = JavaBase64.getEncoder().encodeToString(
            "auto:5af5d0ec-6ea0-3c43-93db-ca3008503bdb@example.com:443".toByteArray()
        )
        val uris = listOf(
            "vmess://$encoded?remarks=first&remarks=second&obfs=websocket&path=%2Fone&path=%2Ftwo",
            "vmess://$encoded?obfs=websocket&tls&tls=1&obfsParam=sni.example",
            "vmess://$encoded?%72emarks=first&remarks=second",
            "vmess://ws+tls:5af5d0ec-6ea0-3c43-93db-ca3008503bdb-0@example.com:443?tlsServerName=first&tlsServerName=second&path=%2Fone&path=%2Ftwo",
        )
        for (uri in uris) {
            val kotlin = parseV2Ray(uri)
            val rust = RustBridge.parseProxy(uri)
            assertEquals("SUCCESS", rust.status)
            assertEquals(kotlin.name ?: "", rust.name)
            assertEquals(kotlin.path ?: "", rust.transportPath)
            assertEquals(kotlin.sni ?: "", rust.sni)
            assertEquals(kotlin.isTLS(), rust.tlsEnabled)
        }
    }

    @Test
    fun testVmessLegacyKitsunebiDifferential() {
        data class KitsunebiCase(val creds: String, val query: String?, val expectedHost: String?)

        val kitsunebiCases = listOf(
            // 1. WebSocket with JSON Host obfsParam and Unicode remarks
            KitsunebiCase(
                "auto:5af5d0ec-6ea0-3c43-93db-ca3008503bdb@183.232.56.161:1202",
                "remarks=*🇯🇵JP%20-355&alterId=0&path=/v2ray&obfs=websocket&tls=1&obfsParam=%7B%22Host%22:%22183.232.56.161%22%7D",
                "183.232.56.161"
            ),
            // 2. WebSocket with SNI in obfsParam (not JSON)
            KitsunebiCase(
                "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443",
                "remarks=FastNode&alterId=4&path=/ws&obfs=websocket&tls=1&obfsParam=sni.example.com",
                null
            ),
            // 3. None obfs (mapped to tcp) with allowInsecure=1
            KitsunebiCase(
                "chacha20-poly1305:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@10.0.0.1:8080",
                "remarks=PlainTCP&alterId=16&path=/&obfs=none&allowInsecure=1",
                null
            ),
            // 4. Plus vs percent decoding and nonstandard tls parameter value (tls=nonstandard)
            KitsunebiCase(
                "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@node.net:443",
                "remarks=Hello+World%20Test&alterId=0&path=/test&obfs=websocket&tls=nonstandard",
                null
            ),
            // 5. JSON obfsParam with MISSING "Host" field
            KitsunebiCase(
                "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@node.net:443",
                "remarks=NoHostJSON&alterId=0&path=/test&obfs=websocket&tls=1&obfsParam=%7B%22Other%22:%22123%22%7D",
                null
            ),
            // 6. JSON obfsParam with EMPTY "Host" field
            KitsunebiCase(
                "aes-128-gcm:b831381d-6324-4d53-ad4f-8cda48b30811@node.net:443",
                "remarks=EmptyHostJSON&alterId=0&path=/test&obfs=websocket&tls=1&obfsParam=%7B%22Host%22:%22%22%7D",
                null
            ),
            // 7. allowInsecure="true" (word) and empty tls parameter value (tls=)
            KitsunebiCase(
                "chacha20-poly1305:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@node.net:443",
                "remarks=AllowInsecureTrue&alterId=8&path=/allow&obfs=none&allowInsecure=true&tls=",
                null
            ),
            // 8. Plain Kitsunebi without query string (preserving CapitalHost.Net casing)
            KitsunebiCase(
                "none:11111111-2222-3333-4444-555555555555@CapitalHost.Net:8080",
                null,
                null
            )
        )

        for ((creds, query, expectedHost) in kitsunebiCases) {
            val b64 = JavaBase64.getUrlEncoder().withoutPadding().encodeToString(creds.toByteArray())
            val uri = if (query != null) "vmess://$b64?$query" else "vmess://$b64"

            val kt = parseV2Ray(uri) as io.nekohasekai.sagernet.fmt.v2ray.VMessBean
            val rs = RustBridge.parseProxy(uri)

            assertEquals("Status mismatch on $uri", "SUCCESS", rs.status)
            assertEquals("Protocol mismatch on $uri", "vmess", rs.protocol)
            assertEquals("ServerAddress mismatch on $uri", kt.serverAddress, rs.server)
            assertEquals("ServerPort mismatch on $uri", kt.serverPort, rs.port)
            assertEquals("UUID mismatch on $uri", kt.uuid ?: "", rs.username)
            assertEquals("Name mismatch on $uri", kt.name ?: "", rs.name)
            assertEquals("Encryption mismatch on $uri", kt.encryption ?: "", rs.encryption)
            assertEquals("AlterId mismatch on $uri", kt.alterId ?: 0, rs.alterId)
            assertEquals("TransportType mismatch on $uri", kt.type ?: "tcp", rs.transportType)
            assertEquals("TLSEnabled mismatch on $uri", kt.isTLS(), rs.tlsEnabled)
            assertEquals("AllowInsecure mismatch on $uri", kt.allowInsecure == true, rs.allowInsecure)
            assertEquals("Path mismatch on $uri", kt.path ?: "", rs.transportPath)
            assertEquals("Host mismatch on $uri", kt.host ?: "", rs.transportHost)
            assertEquals("SNI mismatch on $uri", kt.sni ?: "", rs.sni)
        }
    }
}
