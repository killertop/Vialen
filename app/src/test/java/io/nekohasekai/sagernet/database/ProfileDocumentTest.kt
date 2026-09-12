package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import org.junit.Assert.*
import org.junit.Test

class ProfileDocumentTest {
    @Test fun wireGuardSurvivesFormEditCopyAndReopen() {
        val original = Profile(id = "wg-id", name = "before", type = "wireguard", server = "example.com", port = 51820,
            wireguard = Profile.WireGuard(privateKey = "private", publicKey = "public", address = listOf("10.0.0.1/32"), allowedIps = listOf("192.0.2.0/24"), persistentKeepalive = 45))
        val entity = ProxyEntity().putProfile(original)
        entity.requireBean().name = "after"
        val copied = entity.copy()
        entity.requireBean().name = "later"
        val reopened = ProxyEntity(document = copied.document)
        assertEquals(original.copy(name = "after"), reopened.requireProfile())
        assertEquals("later", entity.requireProfile().name)
    }

    @Test fun transportHeadersSurviveExplicitPutBean() {
        val profile = Profile(id = "vmess-id", name = "original", type = "vmess", server = "example.com", port = 443,
            vmess = Profile.VMess(uuid = "credential"),
            transport = Profile.Transport(type = "ws", path = "/ws", headers = mapOf("X-Test" to listOf("one", "two"))))
        val entity = ProxyEntity().putProfile(profile)
        val form = entity.requireBean()
        form.name = "edited"
        entity.putBean(form)
        assertEquals(profile.copy(name = "edited"), ProxyEntity(document = entity.document).requireProfile())
    }

    @Test fun chainHasIndependentDocumentAndProjection() {
        val chain = ChainBean().apply { initializeDefaultValues(); name = "chain"; proxies = mutableListOf(4L, 2L) }
        val entity = ProxyEntity().putBean(chain)
        val copy = entity.copy()
        chain.proxies.add(9L)
        assertEquals(listOf(4L, 2L), copy.chainBean!!.proxies)
        assertEquals("chain", ProfileDocument.decode(copy.document).kind)
    }
}
