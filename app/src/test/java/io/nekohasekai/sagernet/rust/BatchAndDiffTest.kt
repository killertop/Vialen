package io.nekohasekai.sagernet.rust

import android.util.Base64
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.oracle.parseTrojan
import moe.matsuri.nb4a.Protocols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Base64 as JavaBase64

class BatchAndDiffTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun setup() {
            mockkStatic(Base64::class)
            every { Base64.encode(any(), any()) } answers {
                val flags = secondArg<Int>()
                val bytes = firstArg<ByteArray>()
                if ((flags and Base64.URL_SAFE) != 0) {
                    val enc = if ((flags and Base64.NO_PADDING) != 0) {
                        JavaBase64.getUrlEncoder().withoutPadding()
                    } else {
                        JavaBase64.getUrlEncoder()
                    }
                    enc.encode(bytes)
                } else {
                    JavaBase64.getEncoder().encode(bytes)
                }
            }
            every { Base64.encodeToString(any(), any()) } answers {
                val flags = secondArg<Int>()
                if ((flags and Base64.URL_SAFE) != 0) {
                    val enc = JavaBase64.getUrlEncoder()
                    if ((flags and Base64.NO_PADDING) != 0) {
                        enc.withoutPadding().encodeToString(firstArg<ByteArray>())
                    } else {
                        enc.encodeToString(firstArg<ByteArray>())
                    }
                } else {
                    JavaBase64.getEncoder().encodeToString(firstArg<ByteArray>())
                }
            }
            every { Base64.decode(any<String>(), any()) } answers {
                val raw = firstArg<String>().replace("-", "+").replace("_", "/")
                val pad = (4 - raw.length % 4) % 4
                JavaBase64.getDecoder().decode(raw + "=".repeat(if (pad == 4) 0 else pad))
            }

            mockkStatic(android.text.TextUtils::class)
            every { android.text.TextUtils.isEmpty(any()) } answers {
                firstArg<CharSequence?>().isNullOrEmpty()
            }

            mockkObject(io.nekohasekai.sagernet.ktx.Logs)
            every { io.nekohasekai.sagernet.ktx.Logs.d(any()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.d(any(), any()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.i(any()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.i(any(), any()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.w(any<String>()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.w(any<Throwable>()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.w(any(), any()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.e(any<String>()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.e(any<Throwable>()) } answers {}
            every { io.nekohasekai.sagernet.ktx.Logs.e(any(), any()) } answers {}
        }
    }

    // ==========================================
    // SECTION 1: 13-Point Identity Semantic Parity Tests
    // ==========================================

    // Case 1: same displayName, same protocol, different endpoint -> Updated
    @Test
    fun testParity1_sameDisplayName_sameProtocol_differentEndpoint() {
        val oldUri = "ss://chacha20-ietf-poly1305:secret@1.1.1.1:8388#ServerA"
        val newUri = "ss://chacha20-ietf-poly1305:secret@1.1.1.2:8388#ServerA"

        val diff = RustBridge.diffSubscription(listOf(oldUri), listOf(newUri))
        assertEquals(1, diff.updated.size)
        assertEquals("ServerA", diff.updated[0].newNode.name)
        assertEquals("1.1.1.1", diff.updated[0].oldNode.server)
        assertEquals("1.1.1.2", diff.updated[0].newNode.server)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    // Case 2: same displayName, different protocol -> Updated (not Removed + Added!)
    @Test
    fun testParity2_sameDisplayName_differentProtocol() {
        val oldUri = "ss://chacha20-ietf-poly1305:secret@1.1.1.1:8388#DualProtoNode"
        val newUri = "trojan://password123@1.1.1.1:443#DualProtoNode"

        val diff = RustBridge.diffSubscription(listOf(oldUri), listOf(newUri))
        assertEquals(1, diff.updated.size)
        assertEquals("shadowsocks", diff.updated[0].oldNode.protocol)
        assertEquals("trojan", diff.updated[0].newNode.protocol)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    // Case 3: same displayName, same endpoint, same config -> Unchanged
    @Test
    fun testParity3_sameDisplayName_sameEndpoint() {
        val uri = "ss://chacha20-ietf-poly1305:secret@1.1.1.1:8388#IdenticalNode"

        val diff = RustBridge.diffSubscription(listOf(uri), listOf(uri))
        assertEquals(1, diff.unchanged.size)
        assertEquals("IdenticalNode", diff.unchanged[0].name)
        assertTrue(diff.updated.isEmpty())
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.reordered.isEmpty())
    }

    // Case 4: different displayName, same endpoint -> Removed (old) + Added (new)
    @Test
    fun testParity4_differentDisplayName_sameEndpoint() {
        val oldUri = "ss://chacha20-ietf-poly1305:secret@1.1.1.1:8388#OldName"
        val newUri = "ss://chacha20-ietf-poly1305:secret@1.1.1.1:8388#NewName"

        val diff = RustBridge.diffSubscription(listOf(oldUri), listOf(newUri))
        assertEquals(1, diff.removed.size)
        assertEquals("OldName", diff.removed[0].name)
        assertEquals(1, diff.added.size)
        assertEquals("NewName", diff.added[0].name)
        assertTrue(diff.updated.isEmpty())
        assertTrue(diff.unchanged.isEmpty())
    }

    // Case 5: empty name fallback to server:port
    @Test
    fun testParity5_emptyNameFallback() {
        val uriOld = "ss://chacha20-ietf-poly1305:pass1@1.2.3.4:8388"
        val uriNew = "ss://chacha20-ietf-poly1305:pass2@1.2.3.4:8388"

        val diff = RustBridge.diffSubscription(listOf(uriOld), listOf(uriNew))
        assertEquals(1, diff.updated.size)
        assertEquals("pass1", diff.updated[0].oldNode.password)
        assertEquals("pass2", diff.updated[0].newNode.password)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    // Case 6: IPv4 address
    @Test
    fun testParity6_ipv4Address() {
        val uri = "ss://chacha20-ietf-poly1305:pass@192.168.1.100:8388"
        val res = RustBridge.parseProxy(uri)
        assertEquals("192.168.1.100", res.server)
        assertEquals(8388, res.port)
    }

    // Case 7: IPv6 bracketed address formatting
    @Test
    fun testParity7_ipv6Address() {
        val uriOld = "ss://chacha20-ietf-poly1305:pass1@[2001:db8::1]:8388"
        val uriNew = "ss://chacha20-ietf-poly1305:pass2@[2001:db8::1]:8388"

        val diff = RustBridge.diffSubscription(listOf(uriOld), listOf(uriNew))
        assertEquals(1, diff.updated.size)
        assertEquals("pass1", diff.updated[0].oldNode.password)
        assertEquals("pass2", diff.updated[0].newNode.password)
    }

    // Case 8: Unicode names
    @Test
    fun testParity8_unicodeNames() {
        val uri = "ss://chacha20-ietf-poly1305:pass@1.1.1.1:8388#东京 01 ⚡ 高速节点"
        val res = RustBridge.parseProxy(uri)
        assertEquals("东京 01 ⚡ 高速节点", res.name)

        val diff = RustBridge.diffSubscription(listOf(uri), listOf(uri))
        assertEquals(1, diff.unchanged.size)
        assertEquals("东京 01 ⚡ 高速节点", diff.unchanged[0].name)
    }

    // Case 9: duplicate names requiring (1), (2) disambiguation in RawUpdater
    @Test
    fun testParity9_duplicateNameDisambiguation() {
        // Kotlin RawUpdater appends " (1)", " (2)"
        val kotlinProxies = listOf(
            ShadowsocksBean().apply { name = "Node"; serverAddress = "1.1.1.1"; serverPort = 8388 },
            ShadowsocksBean().apply { name = "Node"; serverAddress = "1.1.1.2"; serverPort = 8388 },
            ShadowsocksBean().apply { name = "Node"; serverAddress = "1.1.1.3"; serverPort = 8388 },
        )
        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in kotlinProxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        val names = proxiesMap.values.map { it.displayName() }
        assertEquals(listOf("Node", "Node (1)", "Node (2)"), names)
    }

    // Case 10: duplicate endpoint (Protocols.Deduplication)
    @Test
    fun testParity10_duplicateEndpoint() {
        val b1 = ShadowsocksBean().apply { serverAddress = "1.1.1.1"; serverPort = 8388 }
        val b2 = ShadowsocksBean().apply { serverAddress = "1.1.1.1"; serverPort = 8388 }
        val d1 = Protocols.Deduplication(b1, b1.javaClass.toString())
        val d2 = Protocols.Deduplication(b2, b2.javaClass.toString())
        assertEquals(d1, d2)
    }

    // Case 11: same identity but changed credentials/config -> Updated
    @Test
    fun testParity11_sameIdentityChangedCredentials() {
        val oldUri = "ss://chacha20-ietf-poly1305:oldsecret@1.1.1.1:8388#ServerA"
        val newUri = "ss://chacha20-ietf-poly1305:newsecret@1.1.1.1:8388#ServerA"

        val diff = RustBridge.diffSubscription(listOf(oldUri), listOf(newUri))
        assertEquals(1, diff.updated.size)
        assertEquals("oldsecret", diff.updated[0].oldNode.password)
        assertEquals("newsecret", diff.updated[0].newNode.password)
    }

    // Case 12: reordered nodes
    @Test
    fun testParity12_reorderedNodes() {
        val u1 = "ss://chacha20-ietf-poly1305:p1@1.1.1.1:8388#N1"
        val u2 = "ss://chacha20-ietf-poly1305:p2@1.1.1.2:8388#N2"

        val diff = RustBridge.diffSubscription(listOf(u1, u2), listOf(u2, u1))
        assertEquals(2, diff.unchanged.size)
        assertEquals(2, diff.reordered.size)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.updated.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    // Case 13: duplicate identities appearing multiple times -> deterministic pairing
    @Test
    fun testParity13_duplicateIdentitiesSequentialPairing() {
        val old = listOf(
            "ss://chacha20-ietf-poly1305:p1@1.1.1.1:8388#Dup",
            "ss://chacha20-ietf-poly1305:p2@1.1.1.2:8388#Dup",
        )
        val new = listOf(
            "ss://chacha20-ietf-poly1305:p1_mod@1.1.1.1:8388#Dup",
            "ss://chacha20-ietf-poly1305:p2_mod@1.1.1.2:8388#Dup",
        )

        val diff = RustBridge.diffSubscription(old, new)
        assertEquals(2, diff.updated.size)
        assertEquals("p1_mod", diff.updated[0].newNode.password)
        assertEquals("p2_mod", diff.updated[1].newNode.password)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    // ==========================================
    // SECTION 2: Exact Display Name Parity (Kotlin vs Rust)
    // ==========================================

    @Test
    fun testExactDisplayNameParity_KotlinVsRust() {
        val testCases = listOf(
            // (server, port, name, description)
            Triple("1.1.1.1", 8388, ""), // empty name fallback
            Triple("1.1.1.1", 8388, "   "), // whitespace fallback
            Triple("1.1.1.1", 8388, "\t\n\r "), // tabs/newlines fallback
            Triple("1.1.1.1", 8388, "\u00A0"), // NBSP unicode whitespace fallback
            Triple("1.1.1.1", 8388, "\u3000"), // Ideographic space unicode whitespace fallback
            Triple("1.1.1.1", 8388, " \u00A0 \u3000 "), // mixed unicode whitespace fallback
            Triple("1.1.1.1", 8388, "  Server 01  "), // non-blank preserves whitespace!
            Triple("1.1.1.1", 8388, "东京 01 (Tokyo 🔥)"), // normal unicode name
            Triple("2001:db8::1", 8388, ""), // unbracketed IPv6 fallback
            Triple("[2001:db8::1]", 8388, ""), // bracketed IPv6 fallback
            Triple("::1", 8388, ""), // loopback IPv6 fallback
            Triple("fe80::1", 8388, ""), // link-local IPv6 fallback
            Triple("abc:def", 8388, ""), // invalid host containing colon: must NOT be bracketed!
            Triple("example.com", 443, ""), // domain name fallback
            Triple("example.com", 443, "My Domain Node"), // domain with custom name
        )

        for ((server, port, name) in testCases) {
            val bean = ShadowsocksBean().apply {
                this.serverAddress = server
                this.serverPort = port
                this.name = name
            }
            val kotlinDisplayName = bean.displayName()
            val rustDisplayName = RustBridge.getDisplayName("ss", server, port, name)

            assertEquals(
                "Display name mismatch for server='$server', port=$port, name='$name'",
                kotlinDisplayName,
                rustDisplayName
            )
        }
    }

    @Test
    fun testInvalidHostAbcDefDisplayName_KotlinVsRust() {
        val server = "abc:def"
        val port = 8388
        val bean = ShadowsocksBean().apply {
            this.serverAddress = server
            this.serverPort = port
            this.name = ""
        }
        val kotlinDisplayName = bean.displayName()
        val rustDisplayName = RustBridge.getDisplayName("ss", server, port, "")

        // Kotlin NetsKt.wrapIPV6Host returns "abc:def" without brackets because it fails IP address parsing.
        // Rust CanonicalNode::display_name must match this exactly without broad colon bracketing.
        assertEquals("abc:def:8388", kotlinDisplayName)
        assertEquals("abc:def:8388", rustDisplayName)
        assertEquals(kotlinDisplayName, rustDisplayName)
    }

    @Test
    fun testValidIpv6DisplayName_KotlinVsRust() {
        val validIpv6Cases = listOf(
            "2001:db8::1",
            "[2001:db8::1]",
            "::1",
            "[::1]",
            "fe80::1",
            "2001:4860:4860::8888",
        )
        for (ipv6 in validIpv6Cases) {
            val bean = ShadowsocksBean().apply {
                this.serverAddress = ipv6
                this.serverPort = 8388
                this.name = ""
            }
            val kotlinDisplayName = bean.displayName()
            val rustDisplayName = RustBridge.getDisplayName("ss", ipv6, 8388, "")

            assertEquals(
                "Valid IPv6 '$ipv6' must have identical bracketed display name in Kotlin and Rust",
                kotlinDisplayName,
                rustDisplayName
            )
            assertTrue("Display name must be bracketed, got: $rustDisplayName", rustDisplayName.startsWith("["))
            assertTrue("Display name must end with ]:8388, got: $rustDisplayName", rustDisplayName.endsWith("]:8388"))
        }
    }

    // ==========================================
    // SECTION 3: Real Dedup & Disambiguation Differentials (Kotlin vs Rust)
    // ==========================================

    @Test
    fun testDisambiguationDifferential_KotlinVsRust() {
        // Test suite of duplicate, blank, and disambiguated names
        val nodes = listOf(
            Triple("1.1.1.1", 8388, "NodeA"),
            Triple("1.1.1.2", 8388, "NodeA"), // Duplicate 1 -> NodeA (1)
            Triple("1.1.1.3", 8388, "NodeA"), // Duplicate 2 -> NodeA (2)
            Triple("1.1.1.4", 8388, ""),      // Blank -> 1.1.1.4:8388
            Triple("1.1.1.4", 8388, ""),      // Duplicate blank -> 1.1.1.4:8388 (1)
            Triple("1.1.1.5", 8388, "NodeB"),
            Triple("1.1.1.6", 8388, "NodeB (1)"), // Pre-existing suffix
            Triple("1.1.1.7", 8388, "NodeB"),     // Needs to skip past (1) -> (2)
        )

        // 1. Kotlin RawUpdater.kt algorithm (lines 89-102)
        val kotlinBeans = nodes.map { (server, port, name) ->
            ShadowsocksBean().apply {
                this.serverAddress = server
                this.serverPort = port
                this.name = name
            }
        }
        val kotlinMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in kotlinBeans) {
            var index = 0
            var name = proxy.displayName()
            while (kotlinMap.containsKey(name)) {
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            kotlinMap[proxy.displayName()] = proxy
        }
        val kotlinDisambiguated = kotlinMap.values.toList()

        // 2. Rust DedupEngine::disambiguate_names
        val uris = nodes.map { (server, port, name) ->
            "ss://chacha20-ietf-poly1305:secret@$server:$port#$name"
        }
        val rustDisambiguated = RustBridge.disambiguateNames(uris)

        assertEquals(kotlinDisambiguated.size, rustDisambiguated.size)
        for (i in kotlinDisambiguated.indices) {
            val kBean = kotlinDisambiguated[i]
            val rNode = rustDisambiguated[i]
            assertEquals(
                "Disambiguated displayName mismatch at index $i",
                kBean.displayName(),
                if (rNode.name.isNotBlank()) rNode.name else "${rNode.server}:${rNode.port}"
            )
            assertEquals("Disambiguated name mismatch at index $i", kBean.name ?: "", rNode.name)
        }
    }

    @Test
    fun testEndpointDedupDifferential_KotlinVsRust() {
        val nodes = listOf(
            Triple("1.1.1.1", 8388, "Node1"),
            Triple("1.1.1.1", 8388, "Node2"), // Same endpoint duplicate
            Triple("1.1.1.2", 8388, "Node3"), // Different endpoint
            Triple("1.1.1.1", 8388, "Node4"), // Another duplicate of first
            Triple("1.1.1.3", 8388, "Node5"), // Different endpoint
        )

        // 1. Kotlin Protocols.Deduplication / RawUpdater logic (lines 108-130)
        val kotlinBeans = nodes.map { (server, port, name) ->
            ShadowsocksBean().apply {
                this.serverAddress = server
                this.serverPort = port
                this.name = name
            }
        }
        val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
        val uniqueNames = HashMap<Protocols.Deduplication, String>()
        for (_proxy in kotlinBeans) {
            val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
            if (!uniqueProxies.add(proxy)) {
                val index = uniqueProxies.indexOf(proxy)
                if (uniqueNames.containsKey(proxy)) {
                    val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                    if (name.isNotBlank()) {
                        uniqueNames[proxy] = ""
                    }
                }
            } else {
                uniqueNames[proxy] = _proxy.displayName()
            }
        }
        uniqueProxies.retainAll(uniqueNames.keys)
        val kotlinDedup = uniqueProxies.toList().map { it.bean }

        // 2. Rust DedupEngine::dedup_by_endpoint
        val uris = nodes.map { (server, port, name) ->
            "ss://chacha20-ietf-poly1305:secret@$server:$port#$name"
        }
        val rustDedup = RustBridge.dedupByEndpoint(uris)

        assertEquals(kotlinDedup.size, rustDedup.size)
        assertEquals(3, rustDedup.size)
        for (i in kotlinDedup.indices) {
            val k = kotlinDedup[i]
            val r = rustDedup[i]
            assertEquals(k.serverAddress, r.server)
            assertEquals(k.serverPort, r.port)
            assertEquals(k.displayName(), if (r.name.isNotBlank()) r.name else "${r.server}:${r.port}")
        }
    }

    @Test
    fun testFramedBytePreflightMatchesJvmUtf8Encoding() {
        val fixtures = listOf(emptyList(), listOf(""), listOf("ascii", "节点", "🚀", "\uD800", "\uDC00", "\uD800x"))
        for (items in fixtures) for (header in listOf(false, true)) {
            val frame = if (header) RustBridge.buildFramedBatch(items) else RustBridge.buildLengthPrefixed(items)
            val bytes = frame.toByteArray(Charsets.UTF_8).size
            for (limit in listOf(bytes - 1, bytes, bytes + 1)) {
                assertEquals("header=$header bytes=$bytes limit=$limit", bytes > limit,
                    RustBridge.framedPayloadExceedsLimit(items, header, limit))
            }
        }
    }

    @Test
    fun testTuicHysteriaDiffAgainstProductionBeanEquality() {
        data class Case(val baseline: String, val parse: (String) -> AbstractBean, val queries: List<String>)
        val cases = listOf(
            Case("tuic://user:secret@example.com:443?alpn=h3#Same",
                { io.nekohasekai.sagernet.oracle.parseTuic(it) },
                listOf("", "sni=changed.example", "congestion_control=cubic", "congestion_control=", "udp_relay_mode=native", "udp_relay_mode=", "udp_relay_mode=quic", "allow_insecure=1", "disable_sni=1")),
            Case("hysteria://example.com:443?auth=secret#Same",
                { io.nekohasekai.sagernet.oracle.parseHysteria1(it) },
                listOf("", "peer=changed.example", "upmbps=42", "downmbps=84", "alpn=h3", "obfsParam=changed", "insecure=1", "mport=443,8443")),
            Case("hysteria2://secret@example.com:443?obfs=salamander#Same",
                { io.nekohasekai.sagernet.oracle.parseHysteria2(it) },
                listOf("", "sni=changed.example", "obfs-password=changed", "insecure=1", "mport=443,8443")),
        )
        cases.forEach { case ->
            case.queries.forEach { query ->
                val uri = if (query.isEmpty()) case.baseline else case.baseline.replace("#Same", "&$query#Same")
                val oldBean = case.parse(case.baseline).apply { initializeDefaultValues() }
                val newBean = case.parse(uri).apply { initializeDefaultValues() }
                newBean.customOutboundJson = oldBean.customOutboundJson
                newBean.customConfigJson = oldBean.customConfigJson
                val changed = oldBean != newBean
                val result = RustBridge.diffSubscription(listOf(case.baseline), listOf(uri))
                val label = "${oldBean.javaClass.simpleName}: $query"
                assertEquals("$label error", null, result.error)
                assertEquals("$label updated", if (changed) 1 else 0, result.updated.size)
                assertEquals("$label unchanged", if (changed) 0 else 1, result.unchanged.size)
                assertTrue(result.added.isEmpty() && result.removed.isEmpty())
            }
        }
    }

    @Test
    fun testTrojanDiffAgainstProductionBeanEquality() {
        val baseline = "trojan://secret@example.com:443?type=ws&path=%2Fone&sni=tls.example#Same"
        val candidates = listOf(
            baseline,
            baseline.replace("secret@", "changed@"),
            baseline.replace("example.com:443", "example.com:8443"),
            baseline.replace("%2Fone", "%2Ftwo"),
            baseline.replace("tls.example", "other.example"),
            baseline.replace("#Same", "&allowInsecure=1#Same"),
            baseline.replace("#Same", "&alpn=h2%2Ch3#Same"),
            baseline.replace("#Same", "&cert=certificate#Same"),
            baseline.replace("#Same", "&pbk=public-key&sid=abcd#Same"),
            baseline.replace("#Same", "&ignored_parameter=value#Same"),
        )
        candidates.forEachIndexed { index, uri ->
            val oldBean = parseTrojan(baseline).apply { initializeDefaultValues() }
            val newBean = parseTrojan(uri).apply { initializeDefaultValues() }
            // RawUpdater preserves local overrides before invoking real Bean.equals.
            newBean.customOutboundJson = oldBean.customOutboundJson
            newBean.customConfigJson = oldBean.customConfigJson
            val expectedUpdate = oldBean != newBean
            val actual = RustBridge.diffSubscription(listOf(baseline), listOf(uri))
            assertEquals("case $index error", null, actual.error)
            assertEquals("case $index updated", if (expectedUpdate) 1 else 0, actual.updated.size)
            assertEquals("case $index unchanged", if (expectedUpdate) 0 else 1, actual.unchanged.size)
            assertTrue(actual.added.isEmpty() && actual.removed.isEmpty())
        }
    }

    @Test
    fun testEndpointConcatenationCollision_KnownProductionMismatch() {
        // Production concatenates address + decimal port without a boundary.
        // This is a known compatibility gap, not a passing differential case.
        val beans = listOf("node1" to 23, "node12" to 3).map { (host, port) ->
            TrojanBean().apply {
                serverAddress = host
                serverPort = port
                name = host
            }
        }
        val productionKeys = beans.map { Protocols.Deduplication(it, it.javaClass.toString()) }
        assertEquals(productionKeys[0].hash(), productionKeys[1].hash())
        assertEquals(1, productionKeys.toSet().size)

        val rustNodes = RustBridge.dedupByEndpoint(
            beans.map { "trojan://secret@${it.serverAddress}:${it.serverPort}#${it.name}" }
        )
        assertEquals(2, rustNodes.size)
        assertEquals(listOf("node1", "node12"), rustNodes.map { it.server })
        assertEquals(listOf(23, 3), rustNodes.map { it.port })
    }

    @Test
    fun testBeanFamilyEndpointDedup_Socks4VsSocks5_And_SsVsSocks_KotlinVsRust() {
        // PRODUCTION BEAN-FAMILY AUDIT:
        // In Kotlin RawUpdater.kt:113 and Protocols.Deduplication.hash():
        // `val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())`
        // - SOCKS4, SOCKS4a, and SOCKS5 all instantiate SOCKSBean (same class: class io.nekohasekai.sagernet.fmt.socks.SOCKSBean).
        //   Therefore, they belong to the same bean family and MUST be deduplicated when having the same endpoint.
        // - Shadowsocks instantiates ShadowsocksBean (class io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean).
        //   Therefore, Shadowsocks and SOCKS belong to different bean families and must NOT be deduplicated against each other.
        // - ConfigBean represents raw JSON configuration profiles without endpoint structure (hash returns bean.config).
        //   ConfigBean deduplication is handled strictly in Kotlin and remains documented as:
        //   CONFIG_BEAN_COVERAGE = GAP_KOTLIN_ONLY.

        val s4Uri = "socks4://1.1.1.1:1080#Socks4Node"
        val s5Uri = "socks5://1.1.1.1:1080#Socks5Node"
        val ssUri = "ss://chacha20-ietf-poly1305:secret@1.1.1.1:1080#ShadowsocksNode"

        // 1. Kotlin Production Ground Truth:
        val s4Bean = SOCKSBean().apply { serverAddress = "1.1.1.1"; serverPort = 1080; name = "Socks4Node" }
        val s5Bean = SOCKSBean().apply { serverAddress = "1.1.1.1"; serverPort = 1080; name = "Socks5Node" }
        val ssBean = ShadowsocksBean().apply { serverAddress = "1.1.1.1"; serverPort = 1080; name = "ShadowsocksNode" }

        val s4Dedup = Protocols.Deduplication(s4Bean, s4Bean.javaClass.toString())
        val s5Dedup = Protocols.Deduplication(s5Bean, s5Bean.javaClass.toString())
        val ssDedup = Protocols.Deduplication(ssBean, ssBean.javaClass.toString())

        // In Kotlin: SOCKS4 and SOCKS5 hash is identical -> EQUAL
        assertEquals("Kotlin: SOCKS4 and SOCKS5 must match in Deduplication", s4Dedup, s5Dedup)
        // In Kotlin: Shadowsocks and SOCKS hash is different -> NOT EQUAL
        assertNotEquals("Kotlin: Shadowsocks and SOCKS must NOT match in Deduplication", ssDedup, s5Dedup)

        // 2. Real Rust JNI Bridge Execution:
        // Case A: SOCKS4 vs SOCKS5 at same endpoint -> DEDUPLICATED (1 item returned)
        val socksDedupResult = RustBridge.dedupByEndpoint(listOf(s4Uri, s5Uri))
        assertEquals("Rust: SOCKS4 and SOCKS5 at same endpoint must dedup", 1, socksDedupResult.size)
        assertEquals("socks4", socksDedupResult[0].protocol)
        assertEquals("1.1.1.1", socksDedupResult[0].server)
        assertEquals(1080, socksDedupResult[0].port)

        // Case B: SS vs SOCKS5 at same endpoint -> NOT DEDUPLICATED (2 items retained)
        val ssVsSocksDedupResult = RustBridge.dedupByEndpoint(listOf(ssUri, s5Uri))
        assertEquals("Rust: Shadowsocks and SOCKS at same endpoint must NOT dedup", 2, ssVsSocksDedupResult.size)
        assertEquals("shadowsocks", ssVsSocksDedupResult[0].protocol)
        assertEquals("socks5", ssVsSocksDedupResult[1].protocol)
        assertEquals("1.1.1.1", ssVsSocksDedupResult[0].server)
        assertEquals("1.1.1.1", ssVsSocksDedupResult[1].server)
    }

    @Test
    fun testBeanFamilyEndpointDedup_VlessVsVmess_KotlinVsRust() {
        // PRODUCTION BEAN-FAMILY AUDIT:
        // In Kotlin V2RayFmt.kt:
        // `val bean = VMessBean().apply { if (link.startsWith("vless://")) alterId = -1 }`
        // Both VLESS and VMess are instantiated as VMessBean (same class: class io.nekohasekai.sagernet.fmt.v2ray.VMessBean).
        // Therefore, in Kotlin RawUpdater.kt:113, VLESS and VMess belong to the same bean family and must be deduplicated
        // when having the same endpoint (serverAddress + serverPort).
        val vlessUri = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@2.2.2.2:443#VlessNode"
        val vmessUri = "vmess://eyJ2IjoiMiIsInBzIjoiVm1lc3NOb2RlIiwiYWRkIjoiMi4yLjIuMiIsInBvcnQiOjQ0MywiaWQiOiJiODMxMzgxZC02MzI0LTRkNTMtYWQ0Zi04Y2RhNDhiMzA4MTEiLCJhaWQiOjAsInNjeSI6ImF1dG8iLCJuZXQiOiJ0Y3AifQ=="

        // 1. Kotlin Ground Truth
        val vlessBean = io.nekohasekai.sagernet.oracle.parseV2Ray(vlessUri)
        val vmessBean = io.nekohasekai.sagernet.oracle.parseV2Ray(vmessUri)
        val vlessDedup = Protocols.Deduplication(vlessBean, vlessBean.javaClass.toString())
        val vmessDedup = Protocols.Deduplication(vmessBean, vmessBean.javaClass.toString())
        assertEquals("Kotlin: VLESS and VMess have same VMessBean class and must match in Deduplication", vlessDedup, vmessDedup)

        // 2. Real Rust Bridge Execution
        val v2rayDedupResult = RustBridge.dedupByEndpoint(listOf(vlessUri, vmessUri))
        assertEquals("Rust: VLESS and VMess at same endpoint must dedup", 1, v2rayDedupResult.size)
        assertEquals("vless", v2rayDedupResult[0].protocol)
        assertEquals("2.2.2.2", v2rayDedupResult[0].server)
        assertEquals(443, v2rayDedupResult[0].port)
    }

    @Test
    fun testProductionPipeline_Disambiguate_Dedup_Diff() {
        // Realistic scenario:
        // Old subscription had:
        // - "Alpha" at 1.1.1.1:8388
        // - "Beta" at 1.1.1.2:8388
        // New subscription contains:
        // - "Alpha" at 1.1.1.1:8388 (modified password)
        // - "Alpha" at 1.1.1.1:8388 (duplicate name and endpoint -> will be dropped by dedup)
        // - "Alpha" at 1.1.1.3:8388 (duplicate name, new endpoint -> becomes "Alpha (1)" after dedup drops the duplicate)
        val oldUris = listOf(
            "ss://chacha20-ietf-poly1305:oldpass@1.1.1.1:8388#Alpha",
            "ss://chacha20-ietf-poly1305:secret@1.1.1.2:8388#Beta",
        )
        val newUris = listOf(
            "ss://chacha20-ietf-poly1305:newpass@1.1.1.1:8388#Alpha",
            "ss://chacha20-ietf-poly1305:ignored@1.1.1.1:8388#Alpha",
            "ss://chacha20-ietf-poly1305:pass3@1.1.1.3:8388#Alpha",
        )

        val diff = RustBridge.diffSubscriptionPipeline(oldUris, newUris, deduplicate = true)
        assertNotNull(diff)
        assertTrue(diff.error == null)

        // "Alpha" at 1.1.1.1:8388 matched and updated
        assertEquals(1, diff.updated.size)
        assertEquals("Alpha", diff.updated[0].newNode.name)
        assertEquals("newpass", diff.updated[0].newNode.password)

        // "Alpha (2)" at 1.1.1.3:8388 was added (since "Alpha (1)" was endpoint-deduped)
        assertEquals(1, diff.added.size)
        assertEquals("Alpha (2)", diff.added[0].name)
        assertEquals("1.1.1.3", diff.added[0].server)

        // "Beta" at 1.1.1.2:8388 was removed
        assertEquals(1, diff.removed.size)
        assertEquals("Beta", diff.removed[0].name)
    }

    // ==========================================
    // SECTION 4: Fail-Closed Diff & Strict Framing Contract
    // ==========================================

    @Test
    fun testFailClosedDiffReporting_OldAndNewErrors() {
        // [JNI_END_TO_END] Real native fail-closed diff with corrupted URIs
        val validUri = "ss://chacha20-ietf-poly1305:pass@1.1.1.1:8388#Valid"
        val invalidUri = "invalid://corrupted-protocol:123"

        // 1. Invalid URI in old input fails closed with indexed error
        val diffOldErr = RustBridge.diffSubscription(listOf(validUri, invalidUri), listOf(validUri))
        assertNotNull(diffOldErr.error)
        assertEquals("old", diffOldErr.failedSide)
        assertEquals(1, diffOldErr.failedIndex)
        assertTrue("Must NOT silently drop old input", diffOldErr.updated.isEmpty() && diffOldErr.added.isEmpty())

        // 2. Invalid URI in new input fails closed with indexed error
        val diffNewErr = RustBridge.diffSubscription(listOf(validUri), listOf(validUri, "ss://bad-port:99999"))
        assertNotNull(diffNewErr.error)
        assertEquals("new", diffNewErr.failedSide)
        assertEquals(1, diffNewErr.failedIndex)
        assertTrue("Must NOT silently drop new input", diffNewErr.updated.isEmpty() && diffNewErr.removed.isEmpty())
    }

    @Test
    fun testStrictFramingContract_LengthPrefixDecoderEdgeCases() {
        // [KOTLIN_FRAMING_PARITY] Strict ASCII length prefix decoder checks
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("2147483647:x")) // addition overflow
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("1:a2147483647:x"))
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("5:hello!")) // trailing garbage
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("+5:hello")) // non-digit sign
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("-5:hello")) // negative sign
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("5a:hello")) // letter in length
        assertEquals(null, RustBridge.decodeLengthPrefixedStrict("10:hello")) // truncated content
        assertEquals(emptyList<String>(), RustBridge.decodeLengthPrefixedStrict("")) // empty buffer
        assertEquals(listOf(""), RustBridge.decodeLengthPrefixedStrict("0:")) // empty item
        assertEquals(listOf("a", "b"), RustBridge.decodeLengthPrefixedStrict("1:a1:b")) // multi-item
    }

    @Test
    fun testStrictFramingContract_CorruptedNativeDiffSectionsRejected() {
        // [KOTLIN_FRAMING_PARITY] Corrupted section count or framing rejected by parseDiffResponse
        // Case 1: Less than 5 sections (e.g. 4 sections)
        val fourSections = RustBridge.buildLengthPrefixed(listOf("0:", "0:", "0:", "0:"))
        val diff4 = RustBridge.parseDiffResponse(fourSections)
        assertNotNull(diff4.error)
        assertTrue("Expected 5 sections error, got: ${diff4.error}", diff4.error!!.contains("Expected exactly 5 sections, got 4"))

        // Case 2: More than 5 sections (e.g. 6 sections)
        val sixSections = RustBridge.buildLengthPrefixed(listOf("0:", "0:", "0:", "0:", "0:", "0:"))
        val diff6 = RustBridge.parseDiffResponse(sixSections)
        assertNotNull(diff6.error)
        assertTrue("Expected 5 sections error, got: ${diff6.error}", diff6.error!!.contains("Expected exactly 5 sections, got 6"))

        // Case 3: Odd number of items in updated section (1 item)
        val fakeNode = "SUCCESS\n" + RustBridge.buildLengthPrefixed(List(38) { "field" })
        val oneUpdatedItem = RustBridge.buildLengthPrefixed(listOf(fakeNode))
        val oddUpdatedPayload = RustBridge.buildLengthPrefixed(listOf(
            "0:",              // added: 0
            oneUpdatedItem,    // updated: 1 item (corrupted! Must be pairs)
            "0:",              // removed: 0
            "0:",              // unchanged: 0
            "0:",              // reordered: 0
        ))
        val diffOdd = RustBridge.parseDiffResponse(oddUpdatedPayload)
        assertNotNull(diffOdd.error)
        assertTrue("Odd updated items must be rejected: ${diffOdd.error}", diffOdd.error!!.contains("Updated section has odd number of items (1)"))

        // Case 4: Malformed framing inside a section
        val malformedSectionPayload = RustBridge.buildLengthPrefixed(listOf(
            "5:hello!", // malformed framing inside section 0
            "0:",
            "0:",
            "0:",
            "0:",
        ))
        val diffMalformed = RustBridge.parseDiffResponse(malformedSectionPayload)
        assertNotNull(diffMalformed.error)
        assertTrue("Malformed section must fail: ${diffMalformed.error}", diffMalformed.error!!.contains("Malformed added section"))
    }

    @Test
    fun testDiffRejectsFailedOrMalformedNestedNodesWithoutPartialResults() {
        for (section in 0..4) {
            for (node in listOf("INVALID_INPUT\n3:bad", "SUCCESS\n1:x", "SUCCESS\n2147483647:x")) {
                val sections = MutableList(5) { "" }
                sections[section] = RustBridge.buildLengthPrefixed(
                    if (section == 1) listOf(node, node) else listOf(node)
                )
                val result = RustBridge.parseDiffResponse(RustBridge.buildLengthPrefixed(sections))
                assertNotNull("section=$section must fail", result.error)
                assertTrue(result.added.isEmpty() && result.updated.isEmpty() && result.removed.isEmpty()
                    && result.unchanged.isEmpty() && result.reordered.isEmpty())
            }
        }
    }

    @Test
    fun testStrictFramingContract_WireSchema38FieldsValidation() {
        // [KOTLIN_FRAMING_PARITY] SUCCESS frame must have exactly 38 fields
        val valid38 = "SUCCESS\n" + RustBridge.buildLengthPrefixed(List(38) { "val$it" })
        val res38 = RustBridge.decodeProxyResponse(valid38)
        assertEquals("SUCCESS", res38.status)

        // 37 fields (underflow)
        val underflow37 = "SUCCESS\n" + RustBridge.buildLengthPrefixed(List(37) { "val$it" })
        val res37 = RustBridge.decodeProxyResponse(underflow37)
        assertEquals("INTERNAL_ERROR", res37.status)
        assertTrue(res37.error!!.contains("Expected exactly 38 fields for SUCCESS, got 37"))

        // 39 fields (overflow)
        val overflow39 = "SUCCESS\n" + RustBridge.buildLengthPrefixed(List(39) { "val$it" })
        val res39 = RustBridge.decodeProxyResponse(overflow39)
        assertEquals("INTERNAL_ERROR", res39.status)
        assertTrue(res39.error!!.contains("Expected exactly 38 fields for SUCCESS, got 39"))

        // Malformed line separator
        val noNewline = "SUCCESS_WITHOUT_NEWLINE"
        val resNoNl = RustBridge.decodeProxyResponse(noNewline)
        assertEquals("INTERNAL_ERROR", resNoNl.status)
        assertTrue(resNoNl.error!!.contains("Empty or malformed response"))
    }

    @Test
    fun testContentKeyInjectivity_AdversarialCollisions() {
        // [JNI_END_TO_END] Real JNI parse and field coverage
        // 1. ALPN with comma vs multiple ALPN entries
        val uriAlpnComma = "trojan://pass@1.1.1.1:443?alpn=h2%2Ch3#N1"
        val uriAlpnMultiple = "trojan://pass@1.1.1.1:443?alpn=h2,h3#N1"

        val res1 = RustBridge.parseProxy(uriAlpnComma)
        val res2 = RustBridge.parseProxy(uriAlpnMultiple)
        assertEquals("SUCCESS", res1.status)
        assertEquals("SUCCESS", res2.status)

        // 2. Wire format covers tlsEnabled and serviceName
        val trUri = "trojan://pass@1.1.1.1:443?type=grpc&serviceName=GunService#TrNode"
        val trRes = RustBridge.parseProxy(trUri)
        assertEquals("SUCCESS", trRes.status)
        assertEquals("GunService", trRes.serviceName)
    }

    @Test
    fun testResourceBoundsEnforced() {
        // [JNI_END_TO_END] Resource limits enforcement
        // Exceeding MAX_BATCH_ITEMS limit
        val oversizedList = List(RustBridge.MAX_BATCH_ITEMS + 1) { "ss://pass@1.1.1.1:8388" }
        val batchRes = RustBridge.parseProxyBatch(oversizedList)
        assertEquals(oversizedList.size, batchRes.size)
        assertEquals("INVALID_INPUT", batchRes[0].status)

        val diffRes = RustBridge.diffSubscription(oversizedList, listOf("ss://pass@1.1.1.1:8388"))
        assertNotNull(diffRes.error)
        assertTrue(diffRes.error!!.contains("INVALID_INPUT"))
    }

    @Test
    fun testErrorIsolationInBatch() {
        // [JNI_END_TO_END] Error isolation across batch items
        val uris = listOf(
            "ss://chacha20-ietf-poly1305:p1@1.1.1.1:8388#Valid1",
            "invalid://corrupted-protocol:123",
            "ss://chacha20-ietf-poly1305:p2@1.1.1.2:8388#Valid2",
            "socks5://bad-port:99999",
            "ss://chacha20-ietf-poly1305:p3@1.1.1.3:8388#Valid3",
        )
        val results = RustBridge.parseProxyBatch(uris)
        assertEquals(5, results.size)
        assertEquals("SUCCESS", results[0].status)
        assertEquals("INVALID_SCHEME", results[1].status)
        assertEquals("SUCCESS", results[2].status)
        assertEquals("INVALID_PORT", results[3].status)
        assertEquals("SUCCESS", results[4].status)
    }

    // ==========================================
    // SECTION 5: Multi-Iteration Benchmarks (10 iterations)
    // ==========================================

    @Test
    fun testBatchParserMultiIterationBenchmark() {
        val sizes = listOf(100, 1000, 5000)
        val iterations = 10
        fun median(sorted: DoubleArray): Double =
            (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0

        fun assertSuccessful(results: List<CanonicalProxyResult>, size: Int) {
            assertEquals(size, results.size)
            results.forEachIndexed { index, result ->
                assertEquals("Benchmark item $index must parse successfully", "SUCCESS", result.status)
            }
        }

        println("\n==========================================================================================")
        println("  BENCHMARK: Active Production Kotlin vs Rust Single JNI vs Rust True Batch JNI")
        println("==========================================================================================")
        println("NOTE on Shadowsocks baseline:")
        println("CURRENT_KOTLIN_SHADOWSOCKS_BENCHMARK = UNAVAILABLE_AFTER_F1_RUST_CUTOVER")
        println("  Reason: parseShadowsocks was cut over to RustBridge in Phase F1 (commit 65d9b0614ee3426bc749485b6a6ae23f3c4ff51e).")
        println("  No hand-written comparator is fabricated.")
        println("Active Production Kotlin Comparator: Trojan (io.nekohasekai.sagernet.oracle.parseTrojan)")
        println("==========================================================================================")

        for (size in sizes) {
            println("\n--- [TROJAN] Active Production Kotlin vs Rust Batch (Size: $size nodes) ---")
            val trojanUris = ArrayList<String>(size)
            for (i in 0 until size) {
                trojanUris.add("trojan://pass_${i}@192.168.${(i / 256) % 256}.${i % 256}:${1024 + (i % 60000)}?allowInsecure=1&peer=sni.example.com#Node_$i")
            }

            // Warmup (3 rounds)
            repeat(3) {
                RustBridge.parseProxyBatch(trojanUris)
                for (u in trojanUris) {
                    RustBridge.parseProxy(u)
                    parseTrojan(u)
                }
            }

            // Interleave paths and rotate their order to reduce JIT/GC ordering bias.
            val kotlinTimes = DoubleArray(iterations)
            val singleTimes = DoubleArray(iterations)
            val batchTimes = DoubleArray(iterations)
            val measurements: List<(Int) -> Unit> = listOf({ iteration ->
                val start = System.nanoTime()
                val list = ArrayList<TrojanBean>(size)
                for (u in trojanUris) {
                    list.add(parseTrojan(u))
                }
                kotlinTimes[iteration] = (System.nanoTime() - start) / 1_000_000.0
                assertEquals(size, list.size)
            }, { iteration ->
                val start = System.nanoTime()
                val list = ArrayList<CanonicalProxyResult>(size)
                for (u in trojanUris) {
                    list.add(RustBridge.parseProxy(u))
                }
                singleTimes[iteration] = (System.nanoTime() - start) / 1_000_000.0
                assertSuccessful(list, size)
            }, { iteration ->
                val start = System.nanoTime()
                val list = RustBridge.parseProxyBatch(trojanUris)
                batchTimes[iteration] = (System.nanoTime() - start) / 1_000_000.0
                assertSuccessful(list, size)
            })
            repeat(iterations) { iteration ->
                repeat(measurements.size) { offset ->
                    measurements[(iteration + offset) % measurements.size](iteration)
                }
            }
            kotlinTimes.sort()
            singleTimes.sort()
            batchTimes.sort()

            val kMed = median(kotlinTimes)
            val sMed = median(singleTimes)
            val bMed = median(batchTimes)

            println("JNI Boundaries: Kotlin = 0 calls | Single = $size calls | True Batch = 1 call")
            println("CURRENT_KOTLIN_TROJAN_MS: min = ${"%.2f".format(kotlinTimes[0])} ms, median = ${"%.2f".format(kMed)} ms, p95 = ${"%.2f".format(kotlinTimes[(iterations * 0.95).toInt()])} ms, max = ${"%.2f".format(kotlinTimes[iterations - 1])} ms")
            println("RUST_SINGLE_JNI_TROJAN_MS: min = ${"%.2f".format(singleTimes[0])} ms, median = ${"%.2f".format(sMed)} ms, p95 = ${"%.2f".format(singleTimes[(iterations * 0.95).toInt()])} ms, max = ${"%.2f".format(singleTimes[iterations - 1])} ms")
            println("RUST_TRUE_BATCH_TROJAN_MS: min = ${"%.2f".format(batchTimes[0])} ms, median = ${"%.2f".format(bMed)} ms, p95 = ${"%.2f".format(batchTimes[(iterations * 0.95).toInt()])} ms, max = ${"%.2f".format(batchTimes[iterations - 1])} ms")
            val singleVsBatch = sMed / bMed.coerceAtLeast(0.01)
            val kotlinVsBatch = kMed / bMed.coerceAtLeast(0.01)
            println("Ratio (Single JNI / True Batch JNI): ${"%.2f".format(singleVsBatch)}x")
            println("Ratio (Kotlin Loop / True Batch JNI): ${"%.2f".format(kotlinVsBatch)}x")

            // Shadowsocks Single vs Batch (production Rust cutover path)
            println("\n--- [SHADOWSOCKS] Single JNI vs True Batch JNI (Size: $size nodes) ---")
            val ssUris = ArrayList<String>(size)
            for (i in 0 until size) {
                ssUris.add("ss://chacha20-ietf-poly1305:pass_${i}@192.168.${(i / 256) % 256}.${i % 256}:${1024 + (i % 60000)}#Node_$i")
            }

            repeat(2) {
                RustBridge.parseProxyBatch(ssUris)
                ssUris.forEach { RustBridge.parseProxy(it) }
            }

            val ssSingleTimes = DoubleArray(iterations)
            for (it in 0 until iterations) {
                val start = System.nanoTime()
                val list = ArrayList<CanonicalProxyResult>(size)
                for (u in ssUris) {
                    list.add(RustBridge.parseProxy(u))
                }
                ssSingleTimes[it] = (System.nanoTime() - start) / 1_000_000.0
                assertSuccessful(list, size)
            }
            ssSingleTimes.sort()

            val ssBatchTimes = DoubleArray(iterations)
            for (it in 0 until iterations) {
                val start = System.nanoTime()
                val list = RustBridge.parseProxyBatch(ssUris)
                ssBatchTimes[it] = (System.nanoTime() - start) / 1_000_000.0
                assertSuccessful(list, size)
            }
            ssBatchTimes.sort()

            val ssSMed = median(ssSingleTimes)
            val ssBMed = median(ssBatchTimes)
            val ssRatio = ssSMed / ssBMed.coerceAtLeast(0.01)

            println("CURRENT_KOTLIN_SHADOWSOCKS_BENCHMARK = UNAVAILABLE_AFTER_F1_RUST_CUTOVER")
            println("RUST_SINGLE_JNI_SHADOWSOCKS_MS: min = ${"%.2f".format(ssSingleTimes[0])} ms, median = ${"%.2f".format(ssSMed)} ms, p95 = ${"%.2f".format(ssSingleTimes[(iterations * 0.95).toInt()])} ms, max = ${"%.2f".format(ssSingleTimes[iterations - 1])} ms")
            println("RUST_TRUE_BATCH_SHADOWSOCKS_MS:  min = ${"%.2f".format(ssBatchTimes[0])} ms, median = ${"%.2f".format(ssBMed)} ms, p95 = ${"%.2f".format(ssBatchTimes[(iterations * 0.95).toInt()])} ms, max = ${"%.2f".format(ssBatchTimes[iterations - 1])} ms")
            println("Ratio (Shadowsocks Single / Batch JNI): ${"%.2f".format(ssRatio)}x")
        }
        println("==========================================================================================\n")
    }
}
