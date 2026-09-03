package io.nekohasekai.sagernet

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Base64 as JavaBase64

class RemovedProtocolNegativeTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            mockkStatic(Base64::class)
            every { Base64.encode(any(), any()) } answers {
                JavaBase64.getEncoder().encode(firstArg<ByteArray>())
            }
            every { Base64.encodeToString(any(), any()) } answers {
                JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
            }
            every { Base64.decode(any<String>(), any()) } answers {
                val str = firstArg<String>().replace("-", "+").replace("_", "/")
                val pad = (4 - str.length % 4) % 4
                JavaBase64.getDecoder().decode(str + "=".repeat(if (pad == 4) 0 else pad))
            }
            every { Base64.decode(any<ByteArray>(), any()) } answers {
                JavaBase64.getDecoder().decode(firstArg<ByteArray>())
            }
            mockkStatic(TextUtils::class)
            every { TextUtils.isEmpty(any()) } answers {
                firstArg<CharSequence?>().isNullOrEmpty()
            }
            mockkStatic(Log::class)
            every { Log.d(any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            every { Log.w(any(), any<String>()) } returns 0
            every { Log.e(any(), any()) } returns 0
        }
    }

    @Test
    fun testHysteria1FakeTcpRejected() {
        val link = "hysteria://hy1.example.com:36712?protocol=faketcp&auth=myhyauth&upmbps=100&downmbps=200#BadFakeTcp"
        try {
            parseHysteria1(link)
            fail("Should throw IllegalArgumentException on faketcp")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("external plugin mode has been removed") == true)
        }
    }

    @Test
    fun testHysteria1WechatVideoRejected() {
        val link = "hysteria://hy1.example.com:36712?protocol=wechat-video&auth=myhyauth&upmbps=100&downmbps=200#BadWechat"
        try {
            parseHysteria1(link)
            fail("Should throw IllegalArgumentException on wechat-video")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("external plugin mode has been removed") == true)
        }
    }

    @Test
    fun testRemovedProtocolLinksDoNotProduceProxies() = runBlocking {
        val removedLinks = listOf(
            "trojan-go://password@tg.example.com:443#TrojanGoTest",
            "naive+https://user:pass@naive.example.com:443#NaiveTest",
            "mieru://user:pass@mieru.example.com:443#MieruTest",
            "ssh://root:pass@ssh.example.com:22#SSHTest"
        )
        for (link in removedLinks) {
            val result = parseProxies(link)
            assertTrue("Link $link should not be parsed into valid entity, but got $result", result.isEmpty())
        }
    }

    @Test
    fun testAddProfileMenuHasNoRemovedProtocolItems() {
        val menuFile = File("src/main/res/menu/add_profile_menu.xml")
        if (menuFile.exists()) {
            val content = menuFile.readText()
            assertFalse(content.contains("action_new_naive"))
            assertFalse(content.contains("action_new_trojan_go"))
            assertFalse(content.contains("action_new_mieru"))
            assertFalse(content.contains("action_new_ssh"))
            assertFalse(content.contains("action_new_neko"))
        }
    }

    @Test
    fun testLibcoreHasNoTorOrSSHRegistration() {
        val boxInclude = File("../libcore/box_include.go")
        if (boxInclude.exists()) {
            val content = boxInclude.readText()
            assertFalse(content.contains("include_tor.go"))
            assertFalse(content.contains("include_ssh.go"))
            assertFalse(content.contains("github.com/sagernet/sing-box/protocol/tor"))
            assertFalse(content.contains("github.com/sagernet/sing-box/protocol/ssh"))
        }
    }
}
