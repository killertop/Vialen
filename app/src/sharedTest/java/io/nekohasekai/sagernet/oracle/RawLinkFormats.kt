package io.nekohasekai.sagernet.oracle

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.parseUniversal
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import io.nekohasekai.sagernet.ktx.*
suspend fun parseLegacyProxies(text: String): List<AbstractBean> {
    val links = text.split('\n').flatMap { it.trim().split(' ') }
    val linksByLine = text.split('\n').map { it.trim() }

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()

    // Parse both historical tokenization views through one native batch. Keep
    // independent Bean instances for repeated inputs so name disambiguation is safe.
    val sameView = links == linksByLine
    val views = if (sameView) links else links + linksByLine
    val nativeInputs = views.filter(io.nekohasekai.sagernet.fmt.RustProxyParser::supports)
    val nativeResults = io.nekohasekai.sagernet.fmt.RustProxyParser.parseBatch(nativeInputs).items.iterator()

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
        } else if (io.nekohasekai.sagernet.fmt.RustProxyParser.supports(this)) {
            nativeResults.next().onSuccess(entities::add).onFailure { Logs.w(it) }
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
    if (sameView) return entities.onEach { it.initializeDefaultValues() }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
    entities.forEach { it.initializeDefaultValues() }
    entitiesByLine.forEach { it.initializeDefaultValues() }
    return if (entities.size > entitiesByLine.size) entities else entitiesByLine
}
