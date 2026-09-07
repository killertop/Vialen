package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Immutable Persisted Proxy Type ID Regression Test.
 *
 * CRITICAL: The `type` column in `proxy_entities` is a persisted enum stored in Room SQLite database.
 * DO NOT renumber or compact these IDs when protocols are removed.
 * IDs must strictly match historical stable releases with holes preserved.
 */
class PersistedProxyTypeIdTest {

    @Test
    fun testImmutablePersistedTypeIds() {
        // Literal assertions against historical stable IDs
        assertEquals("TYPE_SOCKS must remain 0", 0, ProxyEntity.TYPE_SOCKS)
        assertEquals("TYPE_HTTP must remain 1", 1, ProxyEntity.TYPE_HTTP)
        assertEquals("TYPE_SS must remain 2", 2, ProxyEntity.TYPE_SS)
        assertEquals("TYPE_VMESS must remain 4", 4, ProxyEntity.TYPE_VMESS)
        assertEquals("TYPE_TROJAN must remain 6", 6, ProxyEntity.TYPE_TROJAN)
        assertEquals("TYPE_CHAIN must remain 8", 8, ProxyEntity.TYPE_CHAIN)
        assertEquals("TYPE_HYSTERIA must remain 15", 15, ProxyEntity.TYPE_HYSTERIA)
        assertEquals("TYPE_WG must remain 18", 18, ProxyEntity.TYPE_WG)
        assertEquals("TYPE_SHADOWTLS must remain 19", 19, ProxyEntity.TYPE_SHADOWTLS)
        assertEquals("TYPE_TUIC must remain 20", 20, ProxyEntity.TYPE_TUIC)
        assertEquals("TYPE_ANYTLS must remain 22", 22, ProxyEntity.TYPE_ANYTLS)
        assertEquals("TYPE_CONFIG must remain 998", 998, ProxyEntity.TYPE_CONFIG)
    }
}
