package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.*
import org.junit.Test

class ProfileAdapterTest {
    private val base = Profile(id = "profile-id", name = "東京😀", server = "2001:db8::1", port = 443)
    private val tls = Profile.Tls(serverName = "example.com", insecure = true, alpn = listOf("h2", "http/1.1"), certificate = "certificate")
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun advancedFieldsSurviveVisibleEdits() {
        val profiles = listOf(
            base.copy(type = "socks", socks = Profile.Socks(), udpOverTcp = Profile.UDPOverTCP(version = 1)),
            base.copy(type = "vmess", vmess = Profile.VMess(uuid = uuid), tls = tls.copy(ech = Profile.ECH(config = listOf("config"), queryServerName = "ech.example.com")), multiplex = Profile.Multiplex(protocol = "smux", maxStreams = 8, maxConnections = 3)),
            base.copy(type = "hysteria2", hysteria2 = Profile.Hysteria2("secret", Profile.Obfs("gecko", "secret", 512, 1200), hopInterval = 15, hopIntervalMax = 20, bbrProfile = "aggressive", disableChromeParrot = true), tls = tls),
            base.copy(type = "tuic", tuic = Profile.Tuic(uuid = uuid, password = "secret"), tls = tls.copy(disableSni = true)),
        )
        profiles.forEach { original ->
            val bean = ProfileAdapter.toBean(original)
            bean.name = "edited"
            assertEquals(original.copy(name = "edited"), ProfileAdapter.fromBean(bean, original))
        }
    }

    @Test fun elevenProtocolsRoundTripWithoutNativeOrBeanSerialization() {
        val profiles = listOf(
            base.copy(type = "shadowtls", shadowtls = Profile.ShadowTLS(3, "password"), tls = tls),
            base.copy(type = "socks", socks = Profile.Socks("4a", "user")),
            base.copy(type = "http", http = Profile.Http("user", "password"), tls = tls),
            base.copy(type = "shadowsocks", shadowsocks = Profile.Shadowsocks("aes-128-gcm", "password", "v2ray-plugin", "tls;host=example.com")),
            base.copy(type = "vmess", vmess = Profile.VMess(uuid, "auto", 7, "packetaddr"), tls = tls, transport = Profile.Transport(type = "grpc", serviceName = "proxy-service")),
            base.copy(type = "vless", vless = Profile.Vless(uuid, "xtls-rprx-vision", "xudp"), tls = tls.copy(insecure = false, reality = Profile.Reality("public-key", "ab12"))),
            base.copy(type = "trojan", trojan = Profile.Password("password"), tls = tls, transport = Profile.Transport(type = "ws", host = listOf("a.example", "b.example"), path = "/proxy", maxEarlyData = 2048, earlyDataHeaderName = "Sec-WebSocket-Protocol")),
            base.copy(type = "hysteria2", hysteria2 = Profile.Hysteria2("password", Profile.Obfs(password = "obfs-password"), 100, 200, listOf("443", "5000:6000")), tls = tls),
            base.copy(type = "tuic", tuic = Profile.Tuic(uuid, "password", "bbr", "quic", true), tls = tls),
            base.copy(type = "wireguard", wireguard = Profile.WireGuard("private", "public", "psk", listOf("10.0.0.2/32", "fd00::2/128"), mtu = 1420, reserved = listOf(0, 128, 255))),
            base.copy(type = "anytls", anytls = Profile.Password("password"), tls = tls.copy(fingerprint = "chrome")),
        )
        for (original in profiles) {
            val bean = ProfileAdapter.toBean(original)
            assertEquals(original.type, original, ProfileAdapter.fromBean(bean, original.id))
            assertEquals(original.type, original, ProfileAdapter.fromBean(bean, original))
            assertEquals(original.server, bean.finalAddress)
            assertEquals(original.port, bean.finalPort)
        }
    }

    @Test fun defaultsCannotTurnVlessIntoVmessOrOverwriteZeroValues() {
        val original = base.copy(type = "vless", vless = Profile.Vless(uuid))
        val bean = ProfileAdapter.toBean(original) as VMessBean
        assertEquals(-1, bean.alterId)
        assertEquals("", bean.encryption)
        assertEquals("vless", ProfileAdapter.fromBean(bean).type)
        val wg = base.copy(type = "wireguard", wireguard = Profile.WireGuard("private", "public", address = listOf("10.0.0.2/32"), mtu = 0))
        assertEquals(0, (ProfileAdapter.toBean(wg) as WireGuardBean).mtu)
    }

    @Test fun originalRetainsHeadersAndWideIntegersWhenOtherFieldsAreEdited() {
        val original = base.copy(type = "vmess", vmess = Profile.VMess(uuid, alterId = 4_000_000_000L), tls = tls,
            transport = Profile.Transport(type = "ws", path = "/old", maxEarlyData = 4_000_000_000L, headers = mapOf("Authorization" to listOf("Bearer synthetic"))))
        val bean = ProfileAdapter.toBean(original) as VMessBean
        bean.name = "edited name"
        bean.path = "/new"
        val saved = ProfileAdapter.fromBean(bean, original)
        assertEquals(original.vmess, saved.vmess)
        assertEquals(original.transport!!.headers, saved.transport!!.headers)
        assertEquals(4_000_000_000L, saved.transport.maxEarlyData)
        assertEquals("/new", saved.transport.path)
        assertEquals("edited name", saved.name)
        assertEquals(original.id, saved.id)
        bean.wsMaxEarlyData = 17
        assertEquals(17L, ProfileAdapter.fromBean(bean, original).transport!!.maxEarlyData)
    }

    @Test fun wireGuardAndUnsupportedTlsFieldsRemainAuthoritative() {
        val original = base.copy(type = "wireguard", wireguard = Profile.WireGuard("private", "public", address = listOf("10.0.0.2/32"), allowedIps = listOf("10.0.0.0/8", "fd00::/8"), persistentKeepalive = 25))
        val bean = ProfileAdapter.toBean(original) as WireGuardBean
        bean.peerPublicKey = "edited-public"
        val saved = ProfileAdapter.fromBean(bean, original)
        assertEquals(original.wireguard!!.allowedIps, saved.wireguard!!.allowedIps)
        assertEquals(25L, saved.wireguard.persistentKeepalive)
        assertEquals("edited-public", saved.wireguard.publicKey)

        val hy = base.copy(type = "hysteria2", hysteria2 = Profile.Hysteria2("password"), tls = tls.copy(fingerprint = "chrome", reality = Profile.Reality("key", "ab")))
        val hyBean = ProfileAdapter.toBean(hy) as HysteriaBean
        hyBean.sni = "edited.example"
        val hySaved = ProfileAdapter.fromBean(hyBean, hy)
        assertEquals(hy.tls!!.fingerprint, hySaved.tls!!.fingerprint)
        assertEquals(hy.tls.reality, hySaved.tls.reality)
        assertEquals("edited.example", hySaved.tls.serverName)
    }

    @Test fun projectionDoesNotMutateOriginalAndHasNoHiddenIdentityState() {
        val original = base.copy(type = "http", http = Profile.Http("u", "p"), tls = tls)
        val first = ProfileAdapter.toBean(original) as HttpBean
        val second = ProfileAdapter.toBean(original) as HttpBean
        first.password = "edited"
        assertEquals("p", second.password)
        assertEquals("p", original.http!!.password)
        assertEquals("edited", ProfileAdapter.fromBean(first, original, "replacement-id").http!!.password)
        assertEquals("replacement-id", ProfileAdapter.fromBean(first, original, "replacement-id").id)
        assertEquals("", ProfileAdapter.fromBean(second).id)
    }

    @Test fun newFormRejectsUnmappedImportantOptions() {
        val bean = ProfileAdapter.toBean(base.copy(type = "vmess", vmess = Profile.VMess(uuid))) as VMessBean
        bean.customConfigJson = "{}"
        unsupported { ProfileAdapter.fromBean(bean) }
        bean.customConfigJson = ""
        bean.enableMux = true
        assertTrue(ProfileAdapter.fromBean(bean).multiplex!!.enabled)
        val tuic = ProfileAdapter.toBean(base.copy(type = "tuic", tuic = Profile.Tuic(uuid, "p"), tls = tls)) as TuicBean
        tuic.disableSNI = true
        assertTrue(ProfileAdapter.fromBean(tuic).tls!!.disableSni)
        val wg = ProfileAdapter.toBean(base.copy(type = "wireguard", wireguard = Profile.WireGuard("p", "k"))) as WireGuardBean
        wg.reserved = "0,1,256"
        unsupported { ProfileAdapter.fromBean(wg) }
    }

    private fun unsupported(block: () -> Unit) {
        try { block(); fail("Expected an explicit unsupported-form error") } catch (_: ProfileAdapter.Unsupported) { }
    }
}
