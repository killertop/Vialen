package io.nekohasekai.sagernet

import android.util.Base64
import io.mockk.every
import io.mockk.mockkStatic
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.socks.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64 as JavaBase64

class SocksProductionCutoverTest {

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
    fun testSocks5StandardAuth() {
        val uri = "socks5://admin:secret123@192.168.1.100:1080#PrimarySocks"
        val bean = parseSOCKS(uri)

        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
        assertEquals("192.168.1.100", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("admin", bean.username)
        assertEquals("secret123", bean.password)
        assertEquals("PrimarySocks", bean.name)
    }

    @Test
    fun testSocks5NoAuth() {
        val uri = "socks://10.0.0.1:1080#AnonymousSocks"
        val bean = parseSOCKS(uri)

        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
        assertEquals("10.0.0.1", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("", bean.username)
        assertEquals("", bean.password)
        assertEquals("AnonymousSocks", bean.name)
    }

    @Test
    fun testSocks4And4a() {
        val uri4 = "socks4://user1@proxy4.example.com:1080#Socks4Node"
        val bean4 = parseSOCKS(uri4)
        assertEquals(SOCKSBean.PROTOCOL_SOCKS4, bean4.protocol)
        assertEquals("proxy4.example.com", bean4.serverAddress)
        assertEquals(1080, bean4.serverPort)
        assertEquals("user1", bean4.username)
        assertEquals("Socks4Node", bean4.name)

        val uri4a = "socks4a://user2@proxy4a.example.com:1081#Socks4aNode"
        val bean4a = parseSOCKS(uri4a)
        assertEquals(SOCKSBean.PROTOCOL_SOCKS4A, bean4a.protocol)
        assertEquals("proxy4a.example.com", bean4a.serverAddress)
        assertEquals(1081, bean4a.serverPort)
        assertEquals("user2", bean4a.username)
        assertEquals("Socks4aNode", bean4a.name)
    }

    @Test
    fun testV2rayNBase64AuthFormat() {
        val auth = JavaBase64.getEncoder().encodeToString("v2user:v2pass".toByteArray())
        val uri = "socks5://$auth@1.2.3.4:1080#V2rayNSocks"
        val bean = parseSOCKS(uri)

        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
        assertEquals("1.2.3.4", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("v2user", bean.username)
        assertEquals("v2pass", bean.password)
        assertEquals("V2rayNSocks", bean.name)
    }

    @Test
    fun testIpv6BracketedHost() {
        val uri = "socks5://user:pass@[2001:db8::1]:1080#IPv6Socks"
        val bean = parseSOCKS(uri)

        assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
        assertEquals("IPv6Socks", bean.name)
    }

    @Test
    fun testSpecialCharactersAndPipes() {
        val pass = "pass|with|pipes:and@special"
        val encodedPass = URLEncoder.encode(pass, "UTF-8")
        val uri = "socks5://myuser:$encodedPass@127.0.0.1:1080#Special|Chars|Node"
        val bean = parseSOCKS(uri)

        assertEquals("127.0.0.1", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
        assertEquals("myuser", bean.username)
        assertEquals(pass, bean.password)
        assertEquals("Special|Chars|Node", bean.name)
    }

    @Test
    fun testInvalidInputsThrowIllegalStateException() {
        val invalidUris = listOf(
            "http://1.1.1.1:80",
            "ss://user:pass@1.1.1.1:8388",
            "socks5://user:pass@1.1.1.1:abc",
            "socks5://user:pass@1.1.1.1:70000",
            "socks5://user:pass@1.1.1.1:0",
            "socks5://",
            "socks5://[unclosed-ipv6:1080"
        )

        for (uri in invalidUris) {
            try {
                parseSOCKS(uri)
                fail("Expected parseSOCKS to throw for $uri")
            } catch (e: IllegalStateException) {
                assertTrue(e.message?.contains("Not supported") == true)
            }
        }
    }

    @Test
    fun testToUriRoundTrip() {
        val original = SOCKSBean().apply {
            protocol = SOCKSBean.PROTOCOL_SOCKS5
            serverAddress = "socks.roundtrip.org"
            serverPort = 1080
            username = "rtuser"
            password = "rtpassword"
            name = "RoundTripSocks"
        }

        val uri = original.toUri()
        val parsed = parseSOCKS(uri)

        assertEquals(original.protocol, parsed.protocol)
        assertEquals(original.serverAddress, parsed.serverAddress)
        assertEquals(original.serverPort, parsed.serverPort)
        assertEquals(original.username, parsed.username)
        assertEquals(original.password, parsed.password)
        assertEquals(original.name, parsed.name)
    }
}
