package io.nekohasekai.sagernet.core

import com.google.gson.annotations.SerializedName

/** Persisted domain data. Protocol fields are independent of Android UI classes. */
data class Profile(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("server") val server: String = "",
    @SerializedName("port") val port: Int = 0,
    @SerializedName("tls") val tls: Tls? = null,
    @SerializedName("transport") val transport: Transport? = null,
    @SerializedName("socks") val socks: Socks? = null,
    @SerializedName("http") val http: Http? = null,
    @SerializedName("shadowsocks") val shadowsocks: Shadowsocks? = null,
    @SerializedName("vmess") val vmess: VMess? = null,
    @SerializedName("vless") val vless: Vless? = null,
    @SerializedName("trojan") val trojan: Password? = null,
    @SerializedName("hysteria2") val hysteria2: Hysteria2? = null,
    @SerializedName("tuic") val tuic: Tuic? = null,
    @SerializedName("wireguard") val wireguard: WireGuard? = null,
    @SerializedName("udp_over_tcp") val udpOverTcp: UDPOverTCP? = null,
    @SerializedName("multiplex") val multiplex: Multiplex? = null,
    @SerializedName("shadowtls") val shadowtls: ShadowTLS? = null,
    @SerializedName("anytls") val anytls: Password? = null,
) {
    fun displayName(): String = name.ifBlank { displayAddress() }
    fun displayAddress(): String = (if (server.contains(':')) "[$server]" else server) + ":$port"

    data class UDPOverTCP(@SerializedName("enabled") val enabled: Boolean = true, @SerializedName("version") val version: Long = 0)
    data class Multiplex(@SerializedName("enabled") val enabled: Boolean = true, @SerializedName("protocol") val protocol: String = "", @SerializedName("max_connections") val maxConnections: Long = 0, @SerializedName("min_streams") val minStreams: Long = 0, @SerializedName("max_streams") val maxStreams: Long = 0, @SerializedName("padding") val padding: Boolean = false)
    data class ECH(@SerializedName("enabled") val enabled: Boolean = true, @SerializedName("config") val config: List<String> = emptyList(), @SerializedName("query_server_name") val queryServerName: String = "")
    data class Tls(
        @SerializedName("enabled") val enabled: Boolean = true,
        @SerializedName("server_name") val serverName: String = "",
        @SerializedName("insecure") val insecure: Boolean = false,
        @SerializedName("alpn") val alpn: List<String> = emptyList(),
        @SerializedName("fingerprint") val fingerprint: String = "",
        @SerializedName("certificate") val certificate: String = "",
        @SerializedName("disable_sni") val disableSni: Boolean = false,
        @SerializedName("ech") val ech: ECH? = null,
        @SerializedName("reality") val reality: Reality? = null,
    )
    data class Reality(
        @SerializedName("public_key") val publicKey: String = "",
        @SerializedName("short_id") val shortId: String = "",
    )
    data class Transport(
        @SerializedName("type") val type: String = "",
        @SerializedName("host") val host: List<String> = emptyList(),
        @SerializedName("headers") val headers: Map<String, List<String>> = emptyMap(),
        @SerializedName("path") val path: String = "",
        @SerializedName("service_name") val serviceName: String = "",
        @SerializedName("max_early_data") val maxEarlyData: Long = 0,
        @SerializedName("early_data_header_name") val earlyDataHeaderName: String = "",
    )
    data class Socks(
        @SerializedName("version") val version: String = "5",
        @SerializedName("username") val username: String = "",
        @SerializedName("password") val password: String = "",
    )
    data class Http(
        @SerializedName("username") val username: String = "",
        @SerializedName("password") val password: String = "",
    )
    data class Shadowsocks(
        @SerializedName("method") val method: String = "",
        @SerializedName("password") val password: String = "",
        @SerializedName("plugin") val plugin: String = "",
        @SerializedName("plugin_options") val pluginOptions: String = "",
    )
    data class VMess(
        @SerializedName("uuid") val uuid: String = "",
        @SerializedName("security") val security: String = "auto",
        @SerializedName("alter_id") val alterId: Long = 0,
        @SerializedName("packet_encoding") val packetEncoding: String = "",
    )
    data class Vless(
        @SerializedName("uuid") val uuid: String = "",
        @SerializedName("flow") val flow: String = "",
        @SerializedName("packet_encoding") val packetEncoding: String = "",
    )
    data class Password(@SerializedName("password") val password: String = "")
    data class Obfs(
        @SerializedName("type") val type: String = "salamander",
        @SerializedName("password") val password: String = "",
        @SerializedName("min_packet_size") val minPacketSize: Long = 0,
        @SerializedName("max_packet_size") val maxPacketSize: Long = 0,
    )
    data class Hysteria2(
        @SerializedName("password") val password: String = "",
        @SerializedName("obfs") val obfs: Obfs? = null,
        @SerializedName("up_mbps") val upMbps: Long = 0,
        @SerializedName("down_mbps") val downMbps: Long = 0,
        @SerializedName("server_ports") val serverPorts: List<String> = emptyList(),
        @SerializedName("hop_interval") val hopInterval: Long = 0,
        @SerializedName("hop_interval_max") val hopIntervalMax: Long = 0,
        @SerializedName("bbr_profile") val bbrProfile: String = "",
        @SerializedName("disable_chrome_parrot") val disableChromeParrot: Boolean = false,
    )
    data class Tuic(
        @SerializedName("uuid") val uuid: String = "",
        @SerializedName("password") val password: String = "",
        @SerializedName("congestion_control") val congestionControl: String = "",
        @SerializedName("udp_relay_mode") val udpRelayMode: String = "",
        @SerializedName("zero_rtt_handshake") val zeroRttHandshake: Boolean = false,
    )
    data class ShadowTLS(
        @SerializedName("version") val version: Long = 3,
        @SerializedName("password") val password: String = "",
    )

    data class WireGuard(
        @SerializedName("private_key") val privateKey: String = "",
        @SerializedName("public_key") val publicKey: String = "",
        @SerializedName("pre_shared_key") val preSharedKey: String = "",
        @SerializedName("address") val address: List<String> = emptyList(),
        @SerializedName("allowed_ips") val allowedIps: List<String> = emptyList(),
        @SerializedName("mtu") val mtu: Long = 0,
        @SerializedName("reserved") val reserved: List<Int> = emptyList(),
        @SerializedName("persistent_keepalive") val persistentKeepalive: Long = 0,
    )
}
