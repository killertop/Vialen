package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
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
        class Standard(
            @SerializedName("protocol_type") val protocol_type: String,
            @SerializedName("server") val server: String,
            @SerializedName("port") val port: Int,
            @SerializedName("username") val username: String,
            @SerializedName("password") val password: String,
            @SerializedName("uuid") val uuid: String,
            @SerializedName("encryption") val encryption: String,
            @SerializedName("alter_id") val alter_id: Int,
            @SerializedName("version") val version: Int,
            @SerializedName("transport_type") val transport_type: String,
            @SerializedName("host") val host: String,
            @SerializedName("path") val path: String,
            @SerializedName("security") val security: String,
            @SerializedName("sni") val sni: String,
            @SerializedName("alpn") val alpn: String,
            @SerializedName("fingerprint") val fingerprint: String,
            @SerializedName("allow_insecure") val allow_insecure: Boolean,
            @SerializedName("reality_key") val reality_key: String,
            @SerializedName("reality_short_id") val reality_short_id: String,
            @SerializedName("ws_early_data") val ws_early_data: Int,
            @SerializedName("early_header") val early_header: String,
            @SerializedName("certificates") val certificates: String,
            @SerializedName("enable_ech") val enable_ech: Boolean,
            @SerializedName("ech_config") val ech_config: String,
            @SerializedName("packet_encoding") val packet_encoding: Int,
        ) : Profile("Standard")

        class Hysteria(
            @SerializedName("server") val server: String,
            @SerializedName("protocol_version") val protocol_version: Int,
            @SerializedName("server_ports") val server_ports: String,
            @SerializedName("auth") val auth: String,
            @SerializedName("auth_type") val auth_type: Int,
            @SerializedName("protocol") val protocol: Int?,
            @SerializedName("obfuscation") val obfuscation: String,
            @SerializedName("sni") val sni: String,
            @SerializedName("alpn") val alpn: String,
            @SerializedName("certificate") val certificate: String,
            @SerializedName("up") val up: Int,
            @SerializedName("down") val down: Int,
            @SerializedName("insecure") val insecure: Boolean,
            @SerializedName("hop_interval") val hop_interval: Int,
            @SerializedName("hop_max") val hop_max: Int?,
            @SerializedName("bbr") val bbr: String?,
            @SerializedName("disable_parrot") val disable_parrot: Boolean?,
            @SerializedName("obfs_type") val obfs_type: String?,
            @SerializedName("obfs_min") val obfs_min: Int?,
            @SerializedName("obfs_max") val obfs_max: Int?,
        ) : Profile("Hysteria")

        class WireGuard(
            @SerializedName("server") val server: String,
            @SerializedName("port") val port: Int,
            @SerializedName("local_address") val local_address: String,
            @SerializedName("private_key") val private_key: String,
            @SerializedName("public_key") val public_key: String,
            @SerializedName("pre_shared_key") val pre_shared_key: String,
            @SerializedName("mtu") val mtu: Int,
            @SerializedName("reserved") val reserved: String,
        ) : Profile("WireGuard")

        class AnyTLS(
            @SerializedName("server") val server: String,
            @SerializedName("port") val port: Int,
            @SerializedName("password") val password: String,
            @SerializedName("sni") val sni: String,
            @SerializedName("insecure") val insecure: Boolean,
            @SerializedName("alpn") val alpn: String,
            @SerializedName("certificate") val certificate: String,
            @SerializedName("fingerprint") val fingerprint: String,
            @SerializedName("ech_config") val ech_config: String,
        ) : Profile("AnyTLS")

        class Custom(
            @SerializedName("config") val config: String,
        ) : Profile("Custom")

    }

    private class Request(
        @SerializedName("version") val version: Int,
        @SerializedName("global_allow_insecure") val globalAllowInsecure: Boolean,
        @SerializedName("profile") val profile: Profile,
    )

    class Snapshot internal constructor(private val wire: String, private val custom: Boolean = false) {
        internal fun profileJson() = JsonParser.parseString(wire).asJsonObject.get("profile").deepCopy()
        fun generate(): CustomSingBoxOption = decode(
            RustBridge.generateOutbound(wire.toByteArray(Charsets.UTF_8)), custom
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
            HttpBean::class.java, TrojanBean::class.java, VMessBean::class.java, ShadowTLSBean::class.java -> (bean as StandardV2RayBean).let {
                val protocolType = when (it) {
                    is HttpBean -> "http"
                    is TrojanBean -> "trojan"
                    is VMessBean -> if (it.isVLESS) "vless" else "vmess"
                    is ShadowTLSBean -> "shadowtls"
                    else -> error("Unsupported standard Bean")
                }
                Profile.Standard(
                    protocolType,
                    it.serverAddress,
                    it.serverPort,
                    if (it is HttpBean) it.username else "",
                    when (it) { is HttpBean -> it.password; is TrojanBean -> it.password; is ShadowTLSBean -> it.password; else -> "" },
                    it.uuid,
                    it.encryption ?: "",
                    if (it is VMessBean) it.alterId else 0,
                    if (it is ShadowTLSBean) it.version else 0,
                    it.type,
                    it.host,
                    it.path,
                    it.security,
                    it.sni,
                    it.alpn,
                    it.utlsFingerprint,
                    it.allowInsecure,
                    it.realityPubKey,
                    it.realityShortId,
                    it.wsMaxEarlyData,
                    it.earlyDataHeaderName,
                    it.certificates,
                    it.enableECH,
                    it.echConfig,
                    it.packetEncoding
                )
            }
            HysteriaBean::class.java -> (bean as HysteriaBean).let {
                Profile.Hysteria(
                    it.serverAddress,
                    it.protocolVersion,
                    it.serverPorts,
                    it.authPayload,
                    it.authPayloadType,
                    it.protocol,
                    it.obfuscation,
                    it.sni,
                    it.alpn,
                    it.caText,
                    it.uploadMbps,
                    it.downloadMbps,
                    it.allowInsecure,
                    it.hopInterval,
                    it.hopIntervalMax,
                    it.bbrProfile,
                    it.disableChromeParrot,
                    it.obfsType,
                    it.obfsMinPacketSize,
                    it.obfsMaxPacketSize
                )
            }
            WireGuardBean::class.java -> (bean as WireGuardBean).let {
                Profile.WireGuard(
                    it.serverAddress,
                    it.serverPort,
                    it.localAddress,
                    it.privateKey,
                    it.peerPublicKey,
                    it.peerPreSharedKey,
                    it.mtu,
                    it.reserved
                )
            }
            AnyTLSBean::class.java -> (bean as AnyTLSBean).let {
                Profile.AnyTLS(
                    it.serverAddress,
                    it.serverPort,
                    it.password,
                    it.sni,
                    it.allowInsecure,
                    it.alpn,
                    it.certificates,
                    it.utlsFingerprint,
                    it.echConfig
                )
            }
            ConfigBean::class.java -> (bean as ConfigBean).let {
                Profile.Custom(
                    gson.toJson(JsonParser.parseString(it.config))
                )
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
        return Snapshot(wire, profile is Profile.Custom)
    }

    internal fun decode(bytes: ByteArray, custom: Boolean = false): CustomSingBoxOption {
        val result = JsonParser.parseString(bytes.decodeToString(throwOnInvalidSequence = true)).asJsonObject
        val version = result.get("version")
        check(version?.isJsonPrimitive == true && version.asJsonPrimitive.isNumber &&
            version.asString == VERSION.toString()) { "Unsupported Rust config result version" }
        check(result.get("status")?.asString == "SUCCESS") { "Rust outbound input rejected" }
        check(result.keySet() == setOf("version", "status", "outbound")) { "Invalid Rust config result" }
        val outbound = result.get("outbound")
        check(outbound?.isJsonObject == true) { "Missing Rust outbound" }
        check(custom || outbound.asJsonObject.get("type")?.asString in setOf("socks", "shadowsocks", "tuic", "http", "trojan", "vmess", "vless", "shadowtls", "hysteria", "hysteria2", "wireguard", "anytls")) {
            "Invalid Rust outbound type"
        }
        return CustomSingBoxOption(outbound.toString())
    }
}
