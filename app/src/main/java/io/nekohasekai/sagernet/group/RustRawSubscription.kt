package io.nekohasekai.sagernet.group

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.RustProxyParser
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.TypeMap
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/** Android Bean codec only. Format parsing, mapping and fallback live in Rust. */
internal object RustRawSubscription {
    private class StoredField(val field: Field, val type: Type = field.genericType)
    private class Projection(type: Class<out AbstractBean>) {
        val constructor = type.getDeclaredConstructor()
        // Resolve via getField to preserve its inherited/shadowed-field selection.
        val fields = type.fields.associate { it.name to StoredField(type.getField(it.name)) }
    }
    // Keys come only from the fixed supported-type switch below; Beans are never cached.
    private val projections = ConcurrentHashMap<Class<out AbstractBean>, Projection>()

    private fun decodeUniversal(fields: JsonObject): AbstractBean {
        val data = fields["data"].asJsonArray
        val bytes = ByteArray(data.size()) { index ->
            val value = data[index].asInt
            require(value in 0..255)
            value.toByte()
        }
        return ProxyEntity(type = TypeMap[fields["type"].asString] ?: error("Unknown universal Bean type"))
            .apply { putByteArray(bytes) }.requireBean()
    }

    fun parse(text: String, fileName: String = "", mode: String = "raw", json: Any? = null): List<AbstractBean>? {
        val input = JsonObject().apply {
            addProperty("version", 1); addProperty("mode", mode)
            addProperty("text", text); addProperty("file_name", fileName)
            add("json_syntax", when (json) {
                is JSONObject, is JSONArray -> JsonParser.parseString(json.toString())
                else -> JsonNull.INSTANCE
            })
        }
        return parseWithCodecs(input, RustBridge::parseRawSubscription, ::decodeUniversal)
    }

    // Keep decoded mutable Beans local to a response position and retry iteration.
    internal fun parseWithCodecs(
        input: JsonObject,
        parseResponse: (ByteArray) -> ByteArray,
        decode: (JsonObject) -> AbstractBean,
    ): List<AbstractBean>? {
        val invalid = linkedSetOf<String>()
        var universalBeans: Array<AbstractBean?>
        var result: JsonObject
        while (true) {
            input.add("invalid_universal", gson.toJsonTree(invalid))
            val bytes = input.toString().encodeToByteArray(throwOnInvalidSequence = true)
            result = JsonParser.parseString(parseResponse(bytes).decodeToString(throwOnInvalidSequence = true)).asJsonObject
            check(result["version"]?.toString() == "1") { "Invalid subscription result version" }
            check(result["status"]?.asString == "SUCCESS") { "Subscription parsing failed: ${result["error"]?.asString}" }
            result["subscription"]?.let { throw SubscriptionFoundException(it.asString) }
            val nodes = checkNotNull(result["nodes"]) { "Missing subscription result" }
            if (nodes.isJsonNull) return null
            universalBeans = arrayOfNulls(nodes.asJsonArray.size())
            val rejected = nodes.asJsonArray.mapIndexedNotNull { index, encoded ->
                val node = encoded.asJsonObject
                if (node["kind"].asString != "Universal") null else {
                    val link = node["fields"].asJsonObject["link"].asString
                    if (runCatching { universalBeans[index] = decode(node["fields"].asJsonObject) }.isFailure) link else null
                }
            }
            if (rejected.isEmpty()) break
            check(invalid.addAll(rejected)) { "Invalid universal codec retry" }
        }
        return result["nodes"].asJsonArray.mapIndexed { index, encoded ->
            val node = encoded.asJsonObject
            val fields = node["fields"].asJsonObject
            val kind = node["kind"].asString
            val bean = when (kind) {
                "Canonical" -> RustProxyParser.toBean(RustBridge.decodeProxyResponse(fields["wire"].asString))
                "Universal" -> checkNotNull(universalBeans[index])
                else -> {
                    val type = when (kind) {
                        "SOCKS" -> SOCKSBean::class.java
                        "HTTP" -> HttpBean::class.java
                        "Shadowsocks" -> ShadowsocksBean::class.java
                        "VMess" -> VMessBean::class.java
                        "Trojan" -> TrojanBean::class.java
                        "AnyTLS" -> AnyTLSBean::class.java
                        "Hysteria" -> HysteriaBean::class.java
                        "TUIC" -> TuicBean::class.java
                        "WireGuard" -> WireGuardBean::class.java
                        "Config" -> ConfigBean::class.java
                        else -> error("Invalid subscription node type")
                    }
                    val projection = projections.computeIfAbsent(type) { Projection(it) }
                    val bean = projection.constructor.newInstance()
                    if (bean is ConfigBean) bean.initializeDefaultValues()
                    // Named public Bean fields are the stable Android storage projection.
                    fields.entrySet().forEach { (key, value) ->
                        val stored = projection.fields[key] ?: throw NoSuchFieldException(key)
                        check(!java.lang.reflect.Modifier.isStatic(stored.field.modifiers))
                        stored.field.set(bean, gson.fromJson(value, stored.type))
                    }
                    if (bean is ConfigBean) bean.config = gson.toJson(JsonParser.parseString(bean.config))
                    bean
                }
            }
            if (node["initialize"].asBoolean) bean.initializeDefaultValues()
            bean
        }
    }
}
