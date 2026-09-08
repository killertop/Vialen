package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CancellationException

/** Conservative JSON subset; uncertain syntax or coercions retain the original Rust path. */
internal object HybridRawSubscription {
    fun parse(text: String, fileName: String = ""): List<AbstractBean>? =
        tryFastJson(text) ?: RustRawSubscription.parse(text, fileName)

    internal fun tryFastJson(text: String): List<AbstractBean>? {
        // Preserve raw subscription format priority even when markers occur inside strings.
        if (text.contains("proxies:") || text.contains("[Interface]")) return null
        if (!OrdinaryJson(text).accepts()) return null
        return try {
            val json = JSONTokener(text).nextValue()
            if (json !is JSONObject && json !is JSONArray) null else parseJSON(json)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retry the untouched input so Rust retains its complete fallback/error contract.
            null
        }
    }

    /** Strings, booleans and small integers only; no decoding or JSON tree allocation. */
    private class OrdinaryJson(private val text: String) {
        private var pos = 0
        private fun peek(): Char = text.getOrNull(pos) ?: '\u0000'
        private fun space() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') pos++
        }
        fun accepts(): Boolean {
            space()
            if (peek() != '{' && peek() != '[') return false
            if (!value(0)) return false
            space()
            return pos == text.length
        }
        private fun value(depth: Int): Boolean {
            space()
            return when (peek()) {
                '{', '[' -> container(depth + 1)
                '"' -> string()
                't' -> word("true")
                'f' -> word("false")
                // Android optString and the current Rust mapper differ on JSON null.
                'n' -> false
                '-', in '0'..'9' -> number()
                else -> false
            }
        }
        private fun word(word: String): Boolean {
            if (!text.startsWith(word, pos)) return false
            pos += word.length
            return true
        }
        private fun string(): Boolean {
            if (peek() != '"') return false
            pos++
            while (pos < text.length) {
                val c = text[pos++]
                when {
                    c == '"' -> return true
                    c == '\\' || c < ' ' -> return false
                    c.isHighSurrogate() -> {
                        if (!peek().isLowSurrogate()) return false
                        pos++
                    }
                    c.isLowSurrogate() -> return false
                }
            }
            return false
        }
        private fun number(): Boolean {
            val start = pos
            if (peek() == '-') pos++
            val digits = pos
            if (peek() == '0') pos++ else {
                if (peek() !in '1'..'9') return false
                do { pos++ } while (peek() in '0'..'9')
            }
            // Keep floating point formatting, overflow and signed zero on Rust.
            return pos - digits <= 9 && !(pos - start == 2 && text[start] == '-' && text[digits] == '0') &&
                peek() != '.' && peek() != 'e' && peek() != 'E'
        }
        private fun container(depth: Int): Boolean {
            if (depth > 120) return false
            val objectValue = peek() == '{'
            val close = if (objectValue) '}' else ']'
            pos++
            space()
            if (peek() == close) { pos++; return true }
            while (true) {
                if (objectValue) {
                    if (!string()) return false
                    space()
                    if (peek() != ':') return false
                    pos++
                }
                if (!value(depth)) return false
                space()
                if (peek() == close) { pos++; return true }
                if (peek() != ',') return false
                pos++
                space()
            }
        }
    }

    private fun ordinaryInteger(value: Any?): Boolean {
        if (value == null || value is Int) return true
        if (value !is String || value.isEmpty()) return false
        val first = if (value[0] == '-') 1 else 0
        return value.length - first in 1..9 && (first until value.length).all { value[it] in '0'..'9' }
    }

    private fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    require(json.opt("server") is String)
                    require(listOf("up_mbps", "down_mbps", "recv_window_conn", "recv_window").all { ordinaryInteger(json.opt(it)) })
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") -> {
                    // Rust's non-string opt coercion uses its map/list representation.
                    require(!json.has("remarks") || json.opt("remarks") is String)
                    require(!json.has("plugin_opts") || json.opt("plugin_opts") is String)
                    // Java accepts hexadecimal/suffixed floating strings that Rust does not.
                    require(ordinaryInteger(json.opt("server_port")))
                    return listOf(json.parseShadowsocks())
                }

                json.has("outbounds") -> {
                    return json.getJSONArray("outbounds")
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                name = it.getStr("tag")
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            json.forEach { _, it ->
                if (isJsonObjectValid(it)) {
                    proxies.addAll(parseJSON(it))
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
