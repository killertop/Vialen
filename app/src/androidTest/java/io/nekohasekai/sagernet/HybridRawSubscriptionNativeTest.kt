package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.HybridRawSubscription
import io.nekohasekai.sagernet.group.RustRawSubscription
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

/** Synthetic parser-only checks: production Rust is the oracle; no HTTP or Room writes. */
@RunWith(AndroidJUnit4::class)
class HybridRawSubscriptionNativeTest {
    @get:org.junit.Rule(order = Int.MIN_VALUE) val benchmarkForeground = BenchmarkForegroundRule()
    private val ss = """{"method":"aes-128-gcm","server":"node.example","server_port":443,"password":"p","remarks":"中文🔥"}"""

    private fun beans(expected: List<AbstractBean>?, actual: List<AbstractBean>?) {
        if (expected == null) { assertNull(actual); return }
        assertNotNull(actual)
        assertEquals(expected.size, actual!!.size)
        expected.zip(actual).forEachIndexed { index, (a, b) ->
            assertEquals("bean[$index] type", a.javaClass, b.javaClass)
            for (field in a.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }) {
                assertEquals("bean[$index].${field.name}", field.get(a), field.get(b))
            }
        }
    }
    private fun errors(expected: Throwable?, actual: Throwable?) {
        var a = expected; var b = actual; var depth = 0
        while (a != null || b != null) {
            assertEquals("cause[$depth] type", a?.javaClass, b?.javaClass)
            assertEquals("cause[$depth] message", a?.message, b?.message)
            a = a?.cause; b = b?.cause; depth++
        }
    }
    private fun same(text: String, fileName: String = "") {
        val expected = runCatching { RustRawSubscription.parse(text, fileName) }
        val actual = runCatching { HybridRawSubscription.parse(text, fileName) }
        assertEquals("input=$text", expected.isSuccess, actual.isSuccess)
        errors(expected.exceptionOrNull(), actual.exceptionOrNull())
        if (expected.isSuccess) beans(expected.getOrNull(), actual.getOrNull())
    }

    @Test fun ordinaryJsonUsesFastPathAndMatchesEveryPublicField() {
        val cases = listOf(
            ss, "[$ss]", "[$ss,[$ss],{}]", " \t\r\n$ss\n",
            """{"server":"node.example:443","up_mbps":10,"down_mbps":20,"auth_str":"p","protocol":"udp"}""",
            """{"server":"node.example:443","up":10,"auth":"cA==","insecure":true}""",
            """{"outbounds":[{"type":"socks","tag":"中文🔥","server":"node.example","server_port":443},{"type":"direct"},{"type":"dns"}]}""",
            """{"server":"node.example","server_port":443,"extra":"é🔥"}""",
            "{}", "[]", """{"unknown":true}""", """{"outbounds":[]}"""
        )
        for (text in cases) {
            assertNotNull("Expected ordinary JSON fast path: $text", HybridRawSubscription.tryFastJson(text))
            same(text)
        }
        for (number in listOf("0", "-1", "2147483647", "2147483648", "-2147483649", "9223372036854775808", "1.5", "1e3")) {
            same(ss.replace("443", number))
        }
    }

    @Test fun complexAndNonJsonInputsRetainRustFallbackAndExactErrors() {
        val rejected = listOf(
            "\uFEFF$ss", "/* comment */$ss", "//comment\n$ss", "#comment\n$ss",
            "{method:'none',server:'x',server_port:443}", "$ss trailing", "[;1,,2,]",
            """{"method":"none","remarks":"\ud83d\ude00"}""",
            """{"method":"none","remarks":"\ud800"}""",
            """{"method":"none","remarks":"line\nnext"}""",
            """{"proxies:":"x","method":"none"}""",
            """{"remarks":"[Interface]","method":"none"}""",
            "{\"remarks\":\"\uD800\"}", "{\"remarks\":\"\uDC00\"}", "$ss\uD800",
            "[".repeat(121) + "{}" + "]".repeat(121),
            "trojan://p@node.example:443#Example",
            "proxies:\n  - {type: trojan, name: Node, server: node.example, port: 443, password: p}",
            "[Interface]\nAddress=10.0.0.1/32\nPrivateKey=p\n[Peer]\nEndpoint=node.example:443\nPublicKey=k",
            "", "null", "true", "123", "{", "[", "{\"x\":1,}", "[{},]",
            "{\"x\":01}", "{\"x\":+1}", "{\"x\":TRUE}", "{\"x\":NaN}", "{\"x\"=1}", "{\"x\":\"a\nb\"}"
        )
        for (text in rejected) {
            assertNull("Expected Rust fallback: $text", HybridRawSubscription.tryFastJson(text))
            same(text, "synthetic.conf")
        }
        for (text in listOf(
            "[$ss,1]", "[$ss,true]", "[$ss,null]", "[$ss,\"{}\"]", "[$ss,\"plain\"]",
            "{\"outbounds\":1}", "{\"server\":\"x\",\"up\":1,\"protocol\":\"faketcp\"}"
        )) same(text)
        for (depth in listOf(119, 120, 121, 128, 129)) {
            same("[".repeat(depth) + "{}" + "]".repeat(depth))
        }
    }

    @Test fun scalarCoercionDuplicateKeysAndConfigSerializationMatchRust() {
        val failures = ArrayList<String>()
        fun probe(text: String) {
            runCatching { same(text) }.exceptionOrNull()?.let { failures.add("$text -> ${it.message}") }
        }
        val values = listOf(
            "null", "true", "false", "0", "-0", "0.0", "-0.0", "1.5", "-1.5",
            "2147483648", "-2147483649", "9223372036854775807", "9223372036854775808",
            "18446744073709551616", "1e3", "1e-999", "1e999", "-1e999", "1.7976931348623157e308",
            "\"\"", "\" \"", "\"中文🔥\"", "\"null\"", "\"1e999\"", "\"2147483648\"",
            "\"udp\"", "\"wechat-video\"", "{}", "[]", "{\"nested\":[1,null,\"x\"]}"
            , "\"0x1.0p4\"", "\"1f\"", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"+443\"", "\" 443 \"", "\"0123\""
        )
        val bases = listOf(
            "\"method\":\"aes-128-gcm\",\"server\":\"x\",\"server_port\":443",
            "\"server\":\"x:443\",\"up\":1",
            "\"server\":\"x\",\"server_port\":443"
        )
        for (base in bases) for (key in listOf(
            "server", "server_port", "method", "remarks", "password", "plugin", "plugin_opts",
            "up_mbps", "down_mbps", "insecure", "disable_mtu_discovery", "protocol", "recv_window",
            "auth", "auth_str", "extra"
        )) for (value in values) {
            val text = "{$base,\"$key\":$value}"
            probe(text)
            probe("[$text]")
        }
        for (value in values) probe("{\"outbounds\":[{\"type\":\"socks\",\"tag\":$value,\"extra\":$value}]}")
        assertEquals(failures.take(30).joinToString("\n"), 0, failures.size)
    }

}
