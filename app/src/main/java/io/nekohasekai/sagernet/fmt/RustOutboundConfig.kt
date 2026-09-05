package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption

/** First config boundary: immutable single-outbound input, no Room/Android/Go reads.
 * Chain, tag, mux, UoT, DNS strategy and custom overlays remain ConfigBuilder-owned.
 * Fields are explicitly named so R8 cannot change the wire schema.
 */
object RustOutboundConfig {
    private val gson = Gson()
    private const val VERSION = 1

    private sealed class Profile(@SerializedName("kind") val kind: String) {
        class Socks(
            @SerializedName("server") val server: String,
            @SerializedName("port") val port: Int,
            @SerializedName("protocol") val protocol: Int,
            @SerializedName("username") val username: String,
            @SerializedName("password") val password: String,
        ) : Profile("Socks")

        class Shadowsocks(
            @SerializedName("server") val server: String,
            @SerializedName("port") val port: Int,
            @SerializedName("method") val method: String,
            @SerializedName("password") val password: String,
            @SerializedName("plugin") val plugin: String,
        ) : Profile("Shadowsocks")

        class Tuic(
            @SerializedName("server") val server: String,
            @SerializedName("port") val port: Int,
            @SerializedName("protocol_version") val protocolVersion: Int,
            @SerializedName("uuid") val uuid: String,
            @SerializedName("token") val token: String,
            @SerializedName("congestion_controller") val congestionController: String,
            @SerializedName("udp_relay_mode") val udpRelayMode: String,
            @SerializedName("reduce_rtt") val reduceRTT: Boolean,
            @SerializedName("sni") val sni: String,
            @SerializedName("alpn") val alpn: String,
            @SerializedName("ca_text") val caText: String,
            @SerializedName("disable_sni") val disableSNI: Boolean,
            @SerializedName("allow_insecure") val allowInsecure: Boolean,
        ) : Profile("Tuic")
    }

    private class Request(
        @SerializedName("version") val version: Int,
        @SerializedName("global_allow_insecure") val globalAllowInsecure: Boolean,
        @SerializedName("profile") val profile: Profile,
    )

    class Snapshot internal constructor(private val wire: String) {
        fun generate(): CustomSingBoxOption = decode(
            RustBridge.generateOutbound(wire.toByteArray(Charsets.UTF_8))
        )
        override fun toString() = "RustOutboundConfig.Snapshot(v$VERSION)"
    }

    /** Null means an explicit, not-yet-migrated input; native errors never silently fall back. */
    fun capture(bean: AbstractBean, globalAllowInsecure: Boolean): Snapshot? {
        val profile = when (bean.javaClass) {
            SOCKSBean::class.java -> (bean as SOCKSBean).let {
                Profile.Socks(it.serverAddress ?: return null, it.serverPort ?: return null,
                    it.protocol ?: return null, it.username ?: return null, it.password ?: return null)
            }
            ShadowsocksBean::class.java -> (bean as ShadowsocksBean).let {
                Profile.Shadowsocks(it.serverAddress ?: return null, it.serverPort ?: return null,
                    it.method ?: return null, it.password ?: return null, it.plugin ?: return null)
            }
            TuicBean::class.java -> (bean as TuicBean).let {
                Profile.Tuic(it.serverAddress ?: return null, it.serverPort ?: return null,
                    it.protocolVersion ?: return null, it.uuid ?: return null, it.token ?: return null,
                    it.congestionController ?: return null, it.udpRelayMode ?: return null,
                    it.reduceRTT ?: return null, it.sni ?: return null, it.alpn ?: return null,
                    it.caText ?: return null, it.disableSNI ?: return null, it.allowInsecure ?: return null)
            }
            else -> return null
        }
        val wire = gson.toJson(Request(VERSION, globalAllowInsecure, profile))
        // Java permits unpaired surrogates, Rust JSON strings do not. Preserve
        // legacy behavior explicitly instead of changing credentials by UTF-8 replacement.
        var i = 0
        while (i < wire.length) {
            val c = wire[i++]
            if (c.isHighSurrogate()) {
                if (i == wire.length || !wire[i++].isLowSurrogate()) return null
            } else if (c.isLowSurrogate()) return null
        }
        return Snapshot(wire)
    }

    internal fun decode(bytes: ByteArray): CustomSingBoxOption {
        val result = JsonParser.parseString(bytes.decodeToString(throwOnInvalidSequence = true)).asJsonObject
        val version = result.get("version")
        check(version?.isJsonPrimitive == true && version.asJsonPrimitive.isNumber &&
            version.asString == VERSION.toString()) { "Unsupported Rust config result version" }
        check(result.get("status")?.asString == "SUCCESS") { "Rust outbound input rejected" }
        check(result.keySet() == setOf("version", "status", "outbound")) { "Invalid Rust config result" }
        val outbound = result.get("outbound")
        check(outbound?.isJsonObject == true) { "Missing Rust outbound" }
        check(outbound.asJsonObject.get("type")?.asString in setOf("socks", "shadowsocks", "tuic")) {
            "Invalid Rust outbound type"
        }
        return CustomSingBoxOption(outbound.toString())
    }
}
