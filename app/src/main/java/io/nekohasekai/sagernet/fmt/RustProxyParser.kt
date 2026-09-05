package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.rust.RustBridge
import io.nekohasekai.sagernet.rust.CanonicalProxyResult

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.fixPluginName
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

/** Single production projection shared by single-link and batch import. */
object RustProxyParser {
    private val schemes = setOf(
        "ss", "socks", "socks4", "socks4a", "socks5", "trojan", "tuic",
        "hysteria", "hysteria2", "hy2", "vless", "vmess"
    )
    fun supports(uri: String): Boolean = uri.contains("://") && uri.substringBefore("://") in schemes

    data class BatchResult(val items: List<Result<AbstractBean>>, val batchCalls: Int, val singleCalls: Int)

    /** Transparent chunking keeps candidate wire limits out of production import semantics. */
    fun parseBatch(uris: List<String>): BatchResult {
        val items = ArrayList<Result<AbstractBean>>(uris.size)
        val chunk = ArrayList<String>()
        var bytes = 16L // More than enough space for the item-count header.
        var batchCalls = 0
        var singleCalls = 0
        fun flush() {
            if (chunk.isEmpty()) return
            batchCalls++
            items.addAll(RustBridge.parseProxyBatch(chunk).map { result -> runCatching { toBean(result) } })
            chunk.clear()
            bytes = 16L
        }
        for (uri in uris) {
            val cost = RustBridge.framedPayloadByteCount(listOf(uri))
            if (chunk.size == RustBridge.MAX_BATCH_ITEMS || bytes + cost > RustBridge.MAX_PAYLOAD_BYTES) flush()
            if (bytes + cost > RustBridge.MAX_PAYLOAD_BYTES) {
                // A single large link was accepted by the previous single-link path.
                singleCalls++
                items.add(runCatching { parse(uri) })
            } else {
                chunk.add(uri)
                bytes += cost
            }
        }
        flush()
        return BatchResult(items, batchCalls, singleCalls)
    }

    fun parse(uri: String): AbstractBean = toBean(RustBridge.parseProxy(uri))

    fun toBean(node: CanonicalProxyResult): AbstractBean {
        require(node.status == "SUCCESS") { "Invalid proxy: ${node.error ?: node.status}" }
        require(node.port in 1..65535) { "Invalid proxy port" }
        val bean: AbstractBean = when (node.protocol) {
            "shadowsocks" -> ShadowsocksBean().apply {
                method = node.username; password = node.password; plugin = node.plugin
                fixPluginName()
            }
            "socks4", "socks4a", "socks5" -> SOCKSBean().apply {
                protocol = when (node.protocol) {
                    "socks4" -> SOCKSBean.PROTOCOL_SOCKS4
                    "socks4a" -> SOCKSBean.PROTOCOL_SOCKS4A
                    else -> SOCKSBean.PROTOCOL_SOCKS5
                }
                username = node.username; password = node.password
            }
            "trojan" -> TrojanBean().apply { password = node.password }
            "vless", "vmess" -> VMessBean().apply {
                uuid = node.username; alterId = node.alterId; encryption = node.encryption
            }
            "tuic" -> TuicBean().apply {
                protocolVersion = 5; uuid = node.username; token = node.password
                sni = node.sni; alpn = node.alpn; allowInsecure = node.allowInsecure
                disableSNI = node.disableSNI; congestionController = node.congestionControl
                udpRelayMode = node.udpRelayMode
            }
            "hysteria1", "hysteria2" -> HysteriaBean().apply {
                protocolVersion = if (node.protocol == "hysteria1") 1 else 2
                serverPorts = node.serverPorts; authPayload = node.authPayload
                authPayloadType = if (protocolVersion == 1 && authPayload.isNotBlank())
                    HysteriaBean.TYPE_STRING else HysteriaBean.TYPE_NONE
                obfuscation = node.obfsPassword; obfsType = node.obfsType
                sni = node.sni; alpn = node.alpn; allowInsecure = node.allowInsecure
                uploadMbps = node.uploadMbps; downloadMbps = node.downloadMbps
                caText = node.certificates; disableChromeParrot = node.disableChromeParrot
                bbrProfile = node.bbrProfile; hopIntervalMax = node.hopIntervalMax
                obfsMinPacketSize = node.obfsMinPacketSize; obfsMaxPacketSize = node.obfsMaxPacketSize
            }
            else -> error("Unsupported canonical protocol: ${node.protocol}")
        }
        bean.serverAddress = node.server
        bean.serverPort = node.port
        bean.name = node.name
        if (bean is StandardV2RayBean) {
            bean.type = node.transportType; bean.host = node.transportHost; bean.path = node.transportPath
            bean.security = if (node.tlsEnabled) "tls" else "none"
            bean.sni = node.sni; bean.alpn = node.alpn; bean.allowInsecure = node.allowInsecure
            bean.certificates = node.certificates; bean.utlsFingerprint = node.utlsFingerprint
            bean.realityPubKey = node.realityPubKey; bean.realityShortId = node.realityShortId
            bean.wsMaxEarlyData = node.wsMaxEarlyData; bean.earlyDataHeaderName = node.earlyDataHeaderName
            bean.packetEncoding = node.packetEncoding
        }
        bean.initializeDefaultValues()
        return bean
    }
}
