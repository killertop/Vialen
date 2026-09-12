package io.nekohasekai.sagernet

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64 as JavaBase64

class ShadowsocksProductionCutoverTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            mockkStatic(Base64::class)
            every { Base64.encode(any(), any()) } answers {
                val flags = secondArg<Int>()
                val bytes = firstArg<ByteArray>()
                if ((flags and Base64.URL_SAFE) != 0) {
                    val enc = if ((flags and Base64.NO_PADDING) != 0) {
                        JavaBase64.getUrlEncoder().withoutPadding()
                    } else {
                        JavaBase64.getUrlEncoder()
                    }
                    enc.encode(bytes)
                } else {
                    JavaBase64.getEncoder().encode(bytes)
                }
            }
            every { Base64.encodeToString(any(), any()) } answers {
                val flags = secondArg<Int>()
                if ((flags and Base64.URL_SAFE) != 0) {
                    val enc = JavaBase64.getUrlEncoder()
                    if ((flags and Base64.NO_PADDING) != 0) {
                        enc.withoutPadding().encodeToString(firstArg<ByteArray>())
                    } else {
                        enc.encodeToString(firstArg<ByteArray>())
                    }
                } else {
                    JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
                }
            }
            every { Base64.decode(any<String>(), any()) } answers {
                val raw = firstArg<String>().replace("-", "+").replace("_", "/")
                val pad = (4 - raw.length % 4) % 4
                JavaBase64.getDecoder().decode(raw + "=".repeat(if (pad == 4) 0 else pad))
            }
        }
    }

    @Test
    fun testSip002StandardBase64() {
        val userinfo = JavaBase64.getEncoder().encodeToString("chacha20-ietf-poly1305:mypassword123".toByteArray())
        val uri = "ss://$userinfo@ss.example.com:8388#ProductionNode"
        val bean = parseShadowsocks(uri)

        assertEquals("ss.example.com", bean.serverAddress)
        assertEquals(8388, bean.serverPort)
        assertEquals("chacha20-ietf-poly1305", bean.method)
        assertEquals("mypassword123", bean.password)
        assertEquals("ProductionNode", bean.name)
        assertTrue(bean.plugin.isEmpty())
    }

    @Test
    fun testSip002UrlSafeBase64Unpadded() {
        val userinfo = JavaBase64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:pass_with_chars_+/=".toByteArray())
        val uri = "ss://$userinfo@192.168.1.1:8443#UrlSafeNode"
        val bean = parseShadowsocks(uri)

        assertEquals("192.168.1.1", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("aes-256-gcm", bean.method)
        assertEquals("pass_with_chars_+/=", bean.password)
        assertEquals("UrlSafeNode", bean.name)
    }

    @Test
    fun testPlainWithSimpleObfsNormalization() {
        val uri = "ss://aes-128-gcm:mysecret@10.0.0.1:443?plugin=simple-obfs%3Bobfs%3Dhttp%3Bobfs-host%3Dexample.com#ObfsNode"
        val bean = parseShadowsocks(uri)

        assertEquals("10.0.0.1", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("aes-128-gcm", bean.method)
        assertEquals("mysecret", bean.password)
        assertEquals("obfs-local;obfs=http;obfs-host=example.com", bean.plugin)
        assertEquals("ObfsNode", bean.name)
    }

    @Test
    fun testDeprecatedWholeAuthorityBase64IsRejected() {
        val raw = "chacha20-ietf-poly1305:synthetic_pass@synthetic.example:9000"
        val b64 = JavaBase64.getEncoder().encodeToString(raw.toByteArray())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            parseShadowsocks("ss://$b64#Deprecated")
        }
    }

    @Test
    fun testIpv6BracketedHost() {
        val key = JavaBase64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val encodedKey = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
        val uri = "ss://2022-blake3-aes-128-gcm:$encodedKey@[2001:db8::1]:8388#IPv6Node"
        val bean = parseShadowsocks(uri)

        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals(8388, bean.serverPort)
        assertEquals("2022-blake3-aes-128-gcm", bean.method)
        assertEquals(key, bean.password)
        assertEquals("IPv6Node", bean.name)
    }

    @Test
    fun testSpecialCharactersAndPipes() {
        val pass = "pass|word|with|pipes|and:colons"
        val encodedPass = URLEncoder.encode(pass, "UTF-8").replace("+", "%20")
        val uri = "ss://aes-256-gcm:$encodedPass@127.0.0.1:8388#Name%7CWith%7CPipes"
        val bean = parseShadowsocks(uri)

        assertEquals("127.0.0.1", bean.serverAddress)
        assertEquals(8388, bean.serverPort)
        assertEquals("aes-256-gcm", bean.method)
        assertEquals(pass, bean.password)
        assertEquals("", bean.plugin)
        assertEquals("Name|With|Pipes", bean.name)
    }

    @Test
    fun testInvalidInputsRejectWithArgumentError() {
        val invalidUris = listOf(
            "socks5://1.1.1.1:1080",
            "http://1.1.1.1:80",
            "ss://user:pass@1.1.1.1:abc",
            "ss://user:pass@1.1.1.1:70000",
            "ss://user:pass@1.1.1.1:0",
            "ss://A@1.1.1.1:8388",
            "ss://A===@1.1.1.1:8388",
            "ss://",
            "ss://[unclosed-ipv6:8388"
        )

        for (uri in invalidUris) {
            try {
                parseShadowsocks(uri)
                fail("Expected parseShadowsocks to throw for $uri")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun testToUriRoundTrip() {
        val original = ShadowsocksBean().apply {
            serverAddress = "ss.roundtrip.org"
            serverPort = 8388
            method = "chacha20-ietf-poly1305"
            password = "roundtrippassword"
            plugin = "obfs-local;obfs=http"
            name = "RoundTripNode"
        }

        val uri = original.toUri()
        val parsed = parseShadowsocks(uri)

        assertEquals(original.serverAddress, parsed.serverAddress)
        assertEquals(original.serverPort, parsed.serverPort)
        assertEquals(original.method, parsed.method)
        assertEquals(original.password, parsed.password)
        assertEquals(original.plugin, parsed.plugin)
        assertEquals(original.name, parsed.name)
    }
}
