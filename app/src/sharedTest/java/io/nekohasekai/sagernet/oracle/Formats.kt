package io.nekohasekai.sagernet.oracle

// Frozen tokenization/dispatch oracle from f784dc7; test sources only.
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import io.nekohasekai.sagernet.ktx.*

suspend fun parseProxies(text: String): List<AbstractBean> {
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (startsWith("sn://")) {
            Logs.d("Try parse universal link: $this")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://"
            )
        ) {
            Logs.d("Try parse socks link: $this")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            Logs.d("Try parse http link: $this")
            runCatching {
                entities.add(parseHttp(this))
            }.onFailure {
                Logs.w(it)
                val clashUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("install-config")
                    .addQueryParameter("url", this)
                    .build()
                    .toString()
                    .replaceFirst("https://", "clash://")
                throw (SubscriptionFoundException(clashUrl))
            }
        } else if (startsWith("vmess://")) {
            Logs.d("Try parse v2ray link: $this")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("vless://")) {
            Logs.d("Try parse vless link: $this")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("trojan://")) {
            Logs.d("Try parse trojan link: $this")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("ss://")) {
            Logs.d("Try parse shadowsocks link: $this")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("hysteria://")) {
            Logs.d("Try parse hysteria1 link: $this")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            Logs.d("Try parse hysteria2 link: $this")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("tuic://")) {
            Logs.d("Try parse TUIC link: $this")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w(it)
            }
        } else if (startsWith("anytls://")) {
            Logs.d("Try parse anytls link: $this")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w(it)
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
//    var isBadLink = false
    if (entities.onEach { it.initializeDefaultValues() }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size) run test@{
        entities.forEachIndexed { index, bean ->
            val lineBean = entitiesByLine[index]
            if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                return@test
            }
        }
    }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}
