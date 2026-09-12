package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

/** Disposable projection for the existing forms. Profile is the authoritative data.
 *
 * [toBean] is deliberately a UI projection, not a serialization format. Existing
 * records MUST be saved with [fromBean] supplying their original Profile; the
 * overload without an original is only for a newly created form. Advanced fields
 * absent from a form remain in the original Profile. No global metadata or native
 * calls are used here; domain validation belongs to CoreClient.
 */
object ProfileAdapter {
    class Unsupported(field: String) : IllegalArgumentException("The current form cannot represent $field")

    fun toBean(profile: Profile): AbstractBean {
        val bean: AbstractBean = when (profile.type) {
            "socks" -> SOCKSBean()
            "http" -> HttpBean()
            "shadowsocks" -> ShadowsocksBean()
            "vmess", "vless" -> VMessBean()
            "trojan" -> TrojanBean()
            "hysteria2" -> HysteriaBean()
            "tuic" -> TuicBean()
            "wireguard" -> WireGuardBean()
            "shadowtls" -> ShadowTLSBean()
            "anytls" -> AnyTLSBean()
            else -> throw Unsupported("protocol type")
        }
        // Initialize before assignment: defaults must never rewrite imported data.
        bean.initializeDefaultValues()
        bean.serverAddress = profile.server
        bean.serverPort = profile.port
        bean.finalAddress = profile.server
        bean.finalPort = profile.port
        bean.name = profile.name
        if (bean is SOCKSBean) bean.sUoT = profile.udpOverTcp?.enabled ?: false
        if (bean is ShadowsocksBean) bean.sUoT = profile.udpOverTcp?.enabled ?: false
        if (bean is StandardV2RayBean) { bean.enableMux = profile.multiplex?.enabled ?: false; bean.muxPadding = profile.multiplex?.padding ?: false; bean.muxType = when(profile.multiplex?.protocol) { "smux" -> 1; "yamux" -> 2; else -> 0 }; bean.muxConcurrency = profile.multiplex?.maxStreams?.toInt() ?: 8; bean.enableECH = profile.tls?.ech?.enabled ?: false; bean.echConfig = profile.tls?.ech?.config?.joinToString("\n").orEmpty() }
        when (bean) {
            is SOCKSBean -> requireNotNull(profile.socks).let {
                bean.protocol = when (it.version) { "4" -> 0; "4a" -> 1; "5" -> 2; else -> throw Unsupported("SOCKS version") }
                bean.username = it.username; bean.password = it.password
            }
            is ShadowsocksBean -> requireNotNull(profile.shadowsocks).let {
                bean.method = it.method; bean.password = it.password
                bean.plugin = it.plugin + if (it.pluginOptions.isNotEmpty()) ";${it.pluginOptions}" else ""
            }
            is HttpBean -> requireNotNull(profile.http).let { bean.username = it.username; bean.password = it.password }
            is TrojanBean -> bean.password = requireNotNull(profile.trojan).password
            is VMessBean -> if (profile.type == "vless") {
                val options = requireNotNull(profile.vless)
                // -1 is solely the current VMess form's VLESS discriminator.
                bean.alterId = -1; bean.uuid = options.uuid; bean.encryption = options.flow
                bean.packetEncoding = packetToForm(options.packetEncoding)
            } else {
                val options = requireNotNull(profile.vmess)
                bean.alterId = projectInt(options.alterId); bean.uuid = options.uuid; bean.encryption = options.security
                bean.packetEncoding = packetToForm(options.packetEncoding)
            }
            is HysteriaBean -> requireNotNull(profile.hysteria2).let {
                bean.protocolVersion = 2; bean.authPayload = it.password
                bean.hopInterval = if (it.hopInterval == 0L) 10 else projectInt(it.hopInterval); bean.hopIntervalMax = projectInt(it.hopIntervalMax); bean.bbrProfile = it.bbrProfile; bean.disableChromeParrot = it.disableChromeParrot
                bean.obfsMinPacketSize = if (it.obfs?.minPacketSize == null || it.obfs.minPacketSize == 0L) 512 else projectInt(it.obfs.minPacketSize); bean.obfsMaxPacketSize = if (it.obfs?.maxPacketSize == null || it.obfs.maxPacketSize == 0L) 1200 else projectInt(it.obfs.maxPacketSize)
                bean.obfsType = it.obfs?.type ?: "salamander"; bean.obfuscation = it.obfs?.password.orEmpty()
                bean.uploadMbps = projectInt(it.upMbps); bean.downloadMbps = projectInt(it.downMbps)
                bean.serverPorts = it.serverPorts.joinToString(",").ifEmpty { profile.port.toString() }
            }
            is TuicBean -> requireNotNull(profile.tuic).let {
                bean.protocolVersion = 5; bean.uuid = it.uuid; bean.token = it.password
                bean.congestionController = it.congestionControl; bean.udpRelayMode = it.udpRelayMode
                bean.reduceRTT = it.zeroRttHandshake; bean.disableSNI = profile.tls?.disableSni ?: false
            }
            is WireGuardBean -> requireNotNull(profile.wireguard).let {
                bean.privateKey = it.privateKey; bean.peerPublicKey = it.publicKey; bean.peerPreSharedKey = it.preSharedKey
                bean.localAddress = it.address.joinToString("\n"); bean.mtu = projectInt(it.mtu)
                bean.reserved = it.reserved.joinToString(",")
            }
            is ShadowTLSBean -> { bean.version = requireNotNull(profile.shadowtls).version.toInt(); bean.password = profile.shadowtls.password }
            is AnyTLSBean -> { bean.password = requireNotNull(profile.anytls).password; bean.echConfig = profile.tls?.ech?.config?.joinToString("\n").orEmpty() }
        }
        val tls = profile.tls
        when (bean) {
            is StandardV2RayBean -> {
                bean.security = if (tls?.enabled == true) { if (tls.reality != null) "reality" else "tls" } else "none"
                bean.sni = tls?.serverName.orEmpty(); bean.alpn = tls?.alpn?.joinToString(",").orEmpty()
                bean.allowInsecure = tls?.insecure ?: false; bean.utlsFingerprint = tls?.fingerprint.orEmpty()
                bean.certificates = tls?.certificate.orEmpty(); bean.realityPubKey = tls?.reality?.publicKey.orEmpty()
                bean.realityShortId = tls?.reality?.shortId.orEmpty()
                val transport = profile.transport
                bean.type = transport?.type ?: "tcp"; bean.host = transport?.host?.joinToString(",").orEmpty()
                bean.path = if (transport?.type == "grpc") transport.serviceName else transport?.path.orEmpty()
                bean.wsMaxEarlyData = projectInt(transport?.maxEarlyData ?: 0)
                bean.earlyDataHeaderName = transport?.earlyDataHeaderName.orEmpty()
            }
            is HysteriaBean -> { bean.sni = tls?.serverName.orEmpty(); bean.alpn = tls?.alpn?.joinToString(",").orEmpty(); bean.allowInsecure = tls?.insecure ?: false; bean.caText = tls?.certificate.orEmpty() }
            is TuicBean -> { bean.sni = tls?.serverName.orEmpty(); bean.alpn = tls?.alpn?.joinToString(",").orEmpty(); bean.allowInsecure = tls?.insecure ?: false; bean.caText = tls?.certificate.orEmpty() }
            is AnyTLSBean -> { bean.sni = tls?.serverName.orEmpty(); bean.alpn = tls?.alpn?.joinToString(",").orEmpty(); bean.allowInsecure = tls?.insecure ?: false; bean.certificates = tls?.certificate.orEmpty(); bean.utlsFingerprint = tls?.fingerprint.orEmpty() }
        }
        return bean
    }

    /** New-form conversion. Never use this overload to resave an existing Profile. */
    fun fromBean(bean: AbstractBean, id: String = ""): Profile {
        if (!bean.customOutboundJson.isNullOrBlank() || !bean.customConfigJson.isNullOrBlank()) throw Unsupported("custom configuration")
        var result = Profile(id = id, name = bean.name.orEmpty(), server = bean.serverAddress.orEmpty(), port = bean.serverPort ?: 0)
        result = when (bean) {
            is SOCKSBean -> {
                result = result.copy(udpOverTcp = if (bean.sUoT == true) Profile.UDPOverTCP() else null)
                result.copy(type = "socks", socks = Profile.Socks(when (bean.protocol ?: 2) { 0 -> "4"; 1 -> "4a"; 2 -> "5"; else -> throw Unsupported("SOCKS version") }, bean.username.orEmpty(), bean.password.orEmpty()))
            }
            is ShadowsocksBean -> {
                result = result.copy(udpOverTcp = if (bean.sUoT == true) Profile.UDPOverTCP() else null)
                val plugin = bean.plugin.orEmpty().split(';', limit = 2)
                result.copy(type = "shadowsocks", shadowsocks = Profile.Shadowsocks(bean.method.orEmpty(), bean.password.orEmpty(), plugin[0], plugin.getOrElse(1) { "" }))
            }
            is ShadowTLSBean -> result.copy(type = "shadowtls", shadowtls = Profile.ShadowTLS(nonnegative(bean.version, "ShadowTLS version"), bean.password.orEmpty()))
            is HttpBean -> result.copy(type = "http", http = Profile.Http(bean.username.orEmpty(), bean.password.orEmpty()))
            is TrojanBean -> result.copy(type = "trojan", trojan = Profile.Password(bean.password.orEmpty()))
            is VMessBean -> if (bean.alterId == -1) result.copy(type = "vless", vless = Profile.Vless(bean.uuid.orEmpty(), bean.encryption.orEmpty(), packetFromForm(bean.packetEncoding ?: 0))) else {
                val alterId = bean.alterId ?: 0
                if (alterId < 0) throw Unsupported("VMess alter ID")
                result.copy(type = "vmess", vmess = Profile.VMess(bean.uuid.orEmpty(), bean.encryption ?: "auto", alterId.toLong(), packetFromForm(bean.packetEncoding ?: 0)))
            }
            is HysteriaBean -> {
                if (bean.protocolVersion != 2) throw Unsupported("Hysteria version")

                result.copy(type = "hysteria2", hysteria2 = Profile.Hysteria2(bean.authPayload.orEmpty(), if (bean.obfuscation.isNullOrEmpty()) null else Profile.Obfs(bean.obfsType ?: "salamander", bean.obfuscation, if (bean.obfsType == "gecko") nonnegative(bean.obfsMinPacketSize, "gecko minimum") else 0, if (bean.obfsType == "gecko") nonnegative(bean.obfsMaxPacketSize, "gecko maximum") else 0), nonnegative(bean.uploadMbps, "upload speed"), nonnegative(bean.downloadMbps, "download speed"), if (bean.serverPorts == result.port.toString()) emptyList() else parts(bean.serverPorts), hopInterval = if (bean.hopInterval == 10) 0 else nonnegative(bean.hopInterval, "hop interval"), hopIntervalMax = nonnegative(bean.hopIntervalMax, "maximum hop interval"), bbrProfile = bean.bbrProfile.orEmpty(), disableChromeParrot = bean.disableChromeParrot ?: false))
            }
            is TuicBean -> {
                if (bean.protocolVersion != 5) throw Unsupported("TUIC advanced options")
                result.copy(type = "tuic", tuic = Profile.Tuic(bean.uuid.orEmpty(), bean.token.orEmpty(), bean.congestionController.orEmpty(), bean.udpRelayMode.orEmpty(), bean.reduceRTT ?: false))
            }
            is WireGuardBean -> result.copy(type = "wireguard", wireguard = Profile.WireGuard(bean.privateKey.orEmpty(), bean.peerPublicKey.orEmpty(), bean.peerPreSharedKey.orEmpty(), parts(bean.localAddress), mtu = nonnegative(bean.mtu, "MTU"), reserved = parts(bean.reserved).map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: throw Unsupported("WireGuard reserved bytes") }))
            is AnyTLSBean -> { result.copy(type = "anytls", anytls = Profile.Password(bean.password.orEmpty())) }
            else -> throw Unsupported("protocol type")
        }
        return when (bean) {
            is StandardV2RayBean -> {
                result = result.copy(multiplex = if (bean.enableMux == true) Profile.Multiplex(protocol = when(bean.muxType) { 1 -> "smux"; 2 -> "yamux"; else -> "h2mux" }, maxStreams = nonnegative(bean.muxConcurrency, "multiplex streams"), padding = bean.muxPadding ?: false) else null)
                val tls = when (bean.security ?: "none") {
                    "none", "" -> null
                    "tls", "reality" -> Profile.Tls(serverName = bean.sni.orEmpty(), insecure = bean.allowInsecure ?: false, alpn = parts(bean.alpn), fingerprint = bean.utlsFingerprint.orEmpty(), certificate = bean.certificates.orEmpty(), reality = if (!bean.realityPubKey.isNullOrEmpty() || bean.security == "reality") Profile.Reality(bean.realityPubKey.orEmpty(), bean.realityShortId.orEmpty()) else null)
                    else -> throw Unsupported("TLS security")
                }
                val kind = bean.type ?: "tcp"
                if ((bean is HttpBean || bean is ShadowTLSBean) && kind != "tcp") throw Unsupported("HTTP proxy transport")
                val transport = when (kind) {
                    "tcp" -> null
                    "grpc" -> Profile.Transport(type = kind, serviceName = bean.path.orEmpty())
                    "ws", "http", "httpupgrade" -> Profile.Transport(type = kind, host = parts(bean.host), path = bean.path.orEmpty(), maxEarlyData = if (kind == "ws") nonnegative(bean.wsMaxEarlyData, "early data") else 0, earlyDataHeaderName = if (kind == "ws") bean.earlyDataHeaderName.orEmpty() else "")
                    else -> throw Unsupported("transport type")
                }
                result.copy(tls = tls?.copy(ech = if (bean.enableECH == true) Profile.ECH(config = bean.echConfig.orEmpty().lines().filter { it.isNotBlank() }) else null), transport = transport)
            }
            is HysteriaBean -> result.copy(tls = Profile.Tls(serverName = bean.sni.orEmpty(), insecure = bean.allowInsecure ?: false, alpn = parts(bean.alpn), certificate = bean.caText.orEmpty()))
            is TuicBean -> result.copy(tls = Profile.Tls(disableSni = bean.disableSNI ?: false, serverName = bean.sni.orEmpty(), insecure = bean.allowInsecure ?: false, alpn = parts(bean.alpn), certificate = bean.caText.orEmpty()))
            is AnyTLSBean -> result.copy(tls = Profile.Tls(ech = bean.echConfig?.takeIf { it.isNotBlank() }?.let { Profile.ECH(config = it.lines().filter { line -> line.isNotBlank() }) }, serverName = bean.sni.orEmpty(), insecure = bean.allowInsecure ?: false, alpn = parts(bean.alpn), certificate = bean.certificates.orEmpty(), fingerprint = bean.utlsFingerprint.orEmpty()))
            else -> result
        }
    }

    /** Merge an edit into authoritative data, retaining fields absent from the form. */
    fun fromBean(bean: AbstractBean, original: Profile, id: String = original.id): Profile {
        val edited = fromBean(bean, id)
        val baseline = fromBean(toBean(original), id)
        if (edited.type != original.type) throw Unsupported("changing protocol on an existing profile")
        return edited.copy(
            udpOverTcp = pick(edited.udpOverTcp, baseline.udpOverTcp, original.udpOverTcp),
            multiplex = if (edited.multiplex == baseline.multiplex) original.multiplex else edited.multiplex?.let { e -> val o = original.multiplex; e.copy(maxConnections = o?.maxConnections ?: 0, minStreams = o?.minStreams ?: 0) },
            name = pick(edited.name, baseline.name, original.name), server = pick(edited.server, baseline.server, original.server), port = pick(edited.port, baseline.port, original.port),
            tls = mergeTls(edited.tls, baseline.tls, original.tls), transport = mergeTransport(edited.transport, baseline.transport, original.transport),
            socks = pick(edited.socks, baseline.socks, original.socks), http = pick(edited.http, baseline.http, original.http), shadowsocks = pick(edited.shadowsocks, baseline.shadowsocks, original.shadowsocks),
            vmess = edited.vmess?.let { e -> val b = requireNotNull(baseline.vmess); val o = requireNotNull(original.vmess); e.copy(alterId = pick(e.alterId, b.alterId, o.alterId)) },
            shadowtls = pick(edited.shadowtls, baseline.shadowtls, original.shadowtls),
            vless = pick(edited.vless, baseline.vless, original.vless), trojan = pick(edited.trojan, baseline.trojan, original.trojan), anytls = pick(edited.anytls, baseline.anytls, original.anytls),
            hysteria2 = edited.hysteria2?.let { e -> val b = requireNotNull(baseline.hysteria2); val o = requireNotNull(original.hysteria2); e.copy(upMbps = pick(e.upMbps, b.upMbps, o.upMbps), downMbps = pick(e.downMbps, b.downMbps, o.downMbps), serverPorts = pick(e.serverPorts, b.serverPorts, o.serverPorts), obfs = pick(e.obfs, b.obfs, o.obfs)) },
            tuic = pick(edited.tuic, baseline.tuic, original.tuic),
            wireguard = edited.wireguard?.let { e -> val b = requireNotNull(baseline.wireguard); val o = requireNotNull(original.wireguard); e.copy(address = pick(e.address, b.address, o.address), mtu = pick(e.mtu, b.mtu, o.mtu), reserved = pick(e.reserved, b.reserved, o.reserved), allowedIps = o.allowedIps, persistentKeepalive = o.persistentKeepalive) },
        )
    }

    private fun mergeTls(e: Profile.Tls?, b: Profile.Tls?, o: Profile.Tls?): Profile.Tls? {
        if (e == b) return o
        if (e == null || b == null || o == null) return e
        return e.copy(ech = pick(e.ech, b.ech, o.ech), disableSni = pick(e.disableSni, b.disableSni, o.disableSni), enabled = pick(e.enabled, b.enabled, o.enabled), serverName = pick(e.serverName, b.serverName, o.serverName), insecure = pick(e.insecure, b.insecure, o.insecure), alpn = pick(e.alpn, b.alpn, o.alpn), fingerprint = pick(e.fingerprint, b.fingerprint, o.fingerprint), certificate = pick(e.certificate, b.certificate, o.certificate), reality = pick(e.reality, b.reality, o.reality))
    }
    private fun mergeTransport(e: Profile.Transport?, b: Profile.Transport?, o: Profile.Transport?): Profile.Transport? {
        if (e == b) return o
        if (e == null || b == null || o == null) return e
        return e.copy(headers = o.headers, host = pick(e.host, b.host, o.host), maxEarlyData = pick(e.maxEarlyData, b.maxEarlyData, o.maxEarlyData))
    }
    private fun <T> pick(edited: T, baseline: T, original: T): T = if (edited == baseline) original else edited
    private fun projectInt(value: Long): Int = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    private fun nonnegative(value: Int?, field: String): Long = (value ?: 0).takeIf { it >= 0 }?.toLong() ?: throw Unsupported(field)
    private fun parts(value: String?): List<String> = value.orEmpty().split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    private fun packetToForm(value: String): Int = when (value) { "" -> 0; "packetaddr" -> 1; "xudp" -> 2; else -> throw Unsupported("packet encoding") }
    private fun packetFromForm(value: Int): String = when (value) { 0 -> ""; 1 -> "packetaddr"; 2 -> "xudp"; else -> throw Unsupported("packet encoding") }
}
