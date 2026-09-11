package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.oracle.LegacyRawUpdater
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.*
import org.junit.Assert.*

abstract class RawSubscriptionCases {
    private fun comparable(nodes: List<AbstractBean>?) = nodes?.map { it.javaClass.name to gson.toJsonTree(it) }
    private fun compare(text:String,file:String="") = runBlocking {
        val reference = runCatching { io.nekohasekai.sagernet.group.RustRawSubscription.parseReference(text, file) }
        val optimized = runCatching { io.nekohasekai.sagernet.group.RustRawSubscription.parse(text, file) }
        assertEquals(text, reference.isSuccess, optimized.isSuccess)
        if (reference.isSuccess) assertEquals(text, comparable(reference.getOrThrow()), comparable(optimized.getOrThrow()))
        else assertEquals(text, reference.exceptionOrNull()!!::class.java, optimized.exceptionOrNull()!!::class.java)
        val old=runCatching { LegacyRawUpdater.parseRaw(text,file) }
        val fresh=runCatching { RawUpdater.parseRaw(text,file) }
        if(old.isSuccess) {assertTrue("Rust failed: ${fresh.exceptionOrNull()} for $text",fresh.isSuccess);assertEquals(text,comparable(old.getOrThrow()),comparable(fresh.getOrThrow()))}
        else {assertTrue("Rust accepted legacy error ${old.exceptionOrNull()} for $text",fresh.isFailure);if(old.exceptionOrNull() is SubscriptionFoundException) assertEquals((old.exceptionOrNull() as SubscriptionFoundException).link,(fresh.exceptionOrNull() as SubscriptionFoundException).link)}
    }
    @Test fun clashProtocolMatrix() {
        for(type in listOf("socks5","http","ss","vmess","vless","trojan","anytls","hysteria","hysteria2","tuic")) {
            for(network in listOf("tcp","ws","h2","http","grpc")) {
                compare("""
                    global-client-fingerprint: firefox
                    proxies:
                      - type: $type
                        name: sample
                        server: example.com
                        port: 443
                        username: user
                        password: pass
                        uuid: 00000000-0000-0000-0000-000000000001
                        cipher: aes-128-gcm
                        alterId: 4
                        tls: true
                        network: $network
                        alpn: [h2, h3]
                        skip-cert-verify: true
                        packet-encoding: xudp
                        ws-opts:
                          headers: {Host: ws.example}
                          path: /ws
                          max-early-data: 2048
                          early-data-header-name: Sec-WebSocket-Protocol
                        h2-opts: {host: [h2.example], path: /h2}
                        http-opts: {path: [/http], headers: {Host: [http.example]}}
                        grpc-opts: {grpc-service-name: service}
                        reality-opts: {public-key: key, short-id: abcd}
                        smux: {enabled: true, max-streams: 8, padding: true}
                        ech-opts: {enable: true}
                        up: 20 Mbps
                        down: 30 Mbps
                        auth-str: auth
                        obfs: obfs
                        obfs-password: secret
                        recv-window: 4000
                        recv-window-conn: 5000
                        disable-mtu-discovery: 1
                        disable-sni: true
                        reduce-rtt: true
                        congestion-controller: bbr
                        udp-relay-mode: native
                """.trimIndent())
            }
        }
    }
    @Test fun yamlAliasesCoercionOrderAndEmptyLists() {
        compare("proxies: []")
        compare("proxies: [{type: vmess, server: x, port: 443, alterId: -1, cipher: none, flow: xtls-rprx-vision}]")
        compare("proxies: [{type: vmess, server: x, port: 443, cipher: none, alterId: -1, flow: xtls-rprx-vision}]")
        compare("""
            shared: &base {server: localhost, port: 0443, password: 1234}
            proxies:
              - <<: *base
                type: http
                tls: yes
                name: true
              - {type: vless, server: x, port: 443, ws-opts: {v2ray-http-upgrade: true}, network: ws}
              - {type: vless, server: x, port: 443, network: ws, ws-opts: {v2ray-http-upgrade: true}}
        """.trimIndent())
        for(plugin in listOf("obfs","v2ray-plugin")) compare("proxies: [{type: ss, server: localhost, port: 443, cipher: dummy, plugin: $plugin, plugin-opts: {mode: true, host: example, path: /a, mux: true}}]")
    }
    @Test fun wireguardRepeatedSectionsMatchLegacy() {
        compare("""
            [Interface]
            Address = 10.0.0.1/32, fd00::1/128
            Address = 10.0.0.2/32
            PrivateKey = private
            MTU = 1380
            [Peer]
            Endpoint = example.com:51820
            PublicKey = pub1
            PresharedKey = secret
            [Peer]
            Endpoint = [2001:db8::1]:51821
            PublicKey = pub2
        """.trimIndent(),"example.conf")
    }
    @Test fun wireguardDefaultsAndInvalidPeers() {
        for (mtu in listOf("MTU = 1380", "", "MTU = nope")) {
            for (endpoint in listOf("example.com:51820", "invalid", "x:0", "x:-1")) {
                compare("[Interface]\nAddress = 10.0.0.1/32\nPrivateKey = p\n$mtu\n[Peer]\nEndpoint = $endpoint\nPublicKey = key")
            }
        }
    }
    @Test fun iniSeparatorsEscapesAndContinuation() {
        for (value in listOf("abc\\ndef", "abc\\u0041def", "abc\\qdef", "abc\\\n  def", "\"abc\"", "abc #comment")) {
            compare("[Interface]\nAddress:10.0.0.1/32\nPrivateKey=$value\nMTU:1420\n[Peer]\nEndpoint:example.com:443\nPublicKey:key")
        }
    }
    @Test fun universalEnvelopesAndMixedViews() {
        val bean = io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply { initializeDefaultValues(); name="Universal 节点"; username="user"; password="pass" }
        val bytes = io.nekohasekai.sagernet.fmt.KryoConverters.serialize(bean)
        val plain = "sn://socks:" + moe.matsuri.nb4a.utils.Util.b64EncodeUrlSafe(bytes)
        val compressed = "sn://socks?" + moe.matsuri.nb4a.utils.Util.b64EncodeUrlSafe(moe.matsuri.nb4a.utils.Util.zlibCompress(bytes,9))
        for (link in listOf(plain, compressed, "sn://unknown?eJwDAAAAAAE=", "sn://socks?bad", "sn://socks:bad")) {
            compare(link)
            compare(link + "\nss://bm9uZTpwYXNz@example.com:443#valid")
            compare(link + " ss://bm9uZTpwYXNz@example.com:443#with spaces")
            compare(java.util.Base64.getEncoder().encodeToString(link.toByteArray()))
        }
    }
    @Test fun permissiveJsonSyntaxMatchesAndroidTokener() {
        for (text in listOf(
            "\ufeff/*prefix*/{server:'x';server_port=>010;type:'socks'} trailing garbage",
            "[; {method:'none', server:'x'},,]",
            "{server:'x',server_port:0x1bb,type:socks}",
            "{server:'x',server_port:443,type:socks,}",
            "{server:'x',server_port:443,type:socks,1:invalid}",
            "# comment\n {server:'x',server_port:443,type:socks}",
            "{method:'none',server:'x',server_port:443,password:123}",
            "{method:'none',server:'x',server_port:' 443 '}",
            "{method:'none',server:'x',server_port:4294967739}",
            "{method:'none',server:'x',server_port:٤٤٣}",
            "[{server:'x',up_mbps:10,protocol:'faketcp'}]",
            "[{server:'x',server_port:443,hello:'\\ud83d\\ude00'}]",
            "[\"{}\"]", "[true,false,null,123]"
        )) compare(text)
    }
    @Test fun jsonAndFallbackFormats() {
        for(text in listOf(
            "{}", "[]", "null", "123", "broken input", "proxies: [invalid",
            """{"method":"aes-128-gcm","server":"ss.example","server_port":443,"password":"pass","remarks":"test","plugin":"obfs-local","plugin_opts":"obfs=http"}""",
            """{"server":"hy.example:443","up_mbps":100,"down_mbps":200,"auth_str":"secret","protocol":"udp"}""",
            """{"outbounds":[{"type":"direct"},{"type":"socks","server":"x","server_port":1080,"tag":"sample"}]}""",
            """[{"server":"x","server_port":443,"type":"anytls"},[{"method":"none","server":"x"}]]""",
            """{server:'x', server_port:443, type:'socks'}""",
            "http://user:pass@example.com:8080#name", "https://user:pass@example.com?sni=test#name", "anytls://secret@example.com?insecure=1&fp=chrome#name",
            "ss://bm9uZTpwYXNz@example.com:443#name", "clash://install-config?url=x", "https://example.com/subscription"
        )) compare(text)
        compare(java.util.Base64.getEncoder().encodeToString("ss://bm9uZTpwYXNz@example.com:443#name".toByteArray()))
    }
}
