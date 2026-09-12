package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.core.CoreClient
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test

/** Same behavior through the Go host on JVM and packaged gomobile on a physical device. */
class MigrationReviewContractTest {
    private val base = Profile(id = "synthetic", type = "vless", server = "127.0.0.1", port = 443,
        vless = Profile.Vless("123e4567-e89b-12d3-a456-426614174000"))
    private val reality = base.copy(tls = Profile.Tls(serverName = "example.com",
        reality = Profile.Reality("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))

    private fun request(profiles: List<Profile>, insecure: Boolean = false) = ConfigSnapshot.assemble(
        1, profiles.mapIndexed { index, p -> (index + 1L) to ProxyEntity(id = index + 1L, groupId = 1).putProfile(p) }.toMap(),
        emptyMap(), profiles.indices.map { it + 1L }, emptyList(),
        ConfigSnapshot.obj("dns" to ConfigSnapshot.obj("direct" to ConfigSnapshot.obj("type" to "local"),
            "remote" to ConfigSnapshot.obj("type" to "local"))),
        ConfigSnapshot.obj("vpn" to false), "probe", insecure)

    @Test fun globalInsecureOnlyOverridesEnabledCertificateTls() {
        val profiles = listOf(reality, base.copy(tls = Profile.Tls()),
            base.copy(tls = Profile.Tls(enabled = false)), base)
        CoreClient.compile(request(profiles))
        val request = request(profiles, insecure = true)
        CoreClient.compile(request)
        val tls = request.getAsJsonArray("profiles").map { it.asJsonObject.getAsJsonObject("tls") }
        assertFalse(tls[0]["insecure"].asBoolean)
        assertTrue(tls[1]["insecure"].asBoolean)
        assertFalse(tls[2]["enabled"].asBoolean)
        assertFalse(tls[2]["insecure"].asBoolean)
        assertNull(tls[3])
        assertFalse(profiles[1].tls!!.insecure)
    }

    @Test fun switchingWsWithHiddenHeadersToGrpcProducesValidProfile() {
        val original = base.copy(transport = Profile.Transport(type = "ws", path = "/old",
            host = listOf("example.com"), headers = mapOf("X-Test" to listOf("synthetic")),
            maxEarlyData = 2048, earlyDataHeaderName = "Sec-WebSocket-Protocol"))
        CoreClient.validate(original)
        val bean = ProfileAdapter.toBean(original) as VMessBean
        bean.type = "grpc"
        bean.path = "service"
        val edited = ProfileAdapter.fromBean(bean, original)
        CoreClient.validate(edited)
        CoreClient.compile(request(listOf(edited)))
        assertEquals(Profile.Transport(type = "grpc", serviceName = "service"), edited.transport)
        assertEquals(listOf("synthetic"), original.transport!!.headers["X-Test"])
    }

    @Test fun realityRemainsEditableThroughTheTlsForm() {
        val bean = ProfileAdapter.toBean(reality) as VMessBean
        assertEquals("tls", bean.security)
        bean.sni = "edited.example"
        val edited = ProfileAdapter.fromBean(bean, reality)
        CoreClient.validate(edited)
        assertEquals(reality.tls!!.reality, edited.tls!!.reality)
        assertEquals("edited.example", edited.tls.serverName)
        bean.realityPubKey = ""
        bean.realityShortId = ""
        val ordinary = ProfileAdapter.fromBean(bean, reality)
        CoreClient.validate(ordinary)
        assertNull(ordinary.tls!!.reality)
    }
}
