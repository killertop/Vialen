package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.rust.RustBridge
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identical production-parser corpus on host Android shadows and real Android JNI. */
abstract class ProtocolAcceptanceCases {
    private fun compareStandard(uris: List<String>) {
        val failures = mutableListOf<String>()
        for (uri in uris) {
            val kt = runCatching { if (uri.startsWith("trojan:")) parseTrojan(uri) else parseV2Ray(uri) }
            val rs = RustBridge.parseProxy(uri)
            fun check(field: String, a: Any?, b: Any?) {
                if (a != b) failures += "$uri :: $field expected=<$a> actual=<$b>"
            }
            // CanonicalNode admits usable u16 endpoints. Legacy Kotlin parsers
            // can return null/zero/out-of-range ports without throwing.
            val admitted = kt.getOrNull()?.serverPort?.let { it in 1..65535 } == true
            check("success", admitted, rs.status == "SUCCESS")
            if (!admitted || rs.status != "SUCCESS") continue
            val bean = kt.getOrThrow()
            check("server", bean.serverAddress, rs.server)
            check("port", bean.serverPort, rs.port)
            check("name", bean.name ?: "", rs.name)
            check("tls", bean.isTLS(), rs.tlsEnabled)
            check("sni", bean.sni ?: "", rs.sni)
            check("alpn", bean.alpn ?: "", rs.alpn)
            check("insecure", bean.allowInsecure ?: false, rs.allowInsecure)
            check("cert", bean.certificates ?: "", rs.certificates)
            check("pbk", bean.realityPubKey ?: "", rs.realityPubKey)
            check("sid", bean.realityShortId ?: "", rs.realityShortId)
            check("fp", bean.utlsFingerprint ?: "", rs.utlsFingerprint)
            check("type", bean.type ?: "tcp", rs.transportType)
            check("host", bean.host ?: "", rs.transportHost)
            check("path", bean.path ?: "", rs.transportPath)
            check("ed", bean.wsMaxEarlyData ?: 0, rs.wsMaxEarlyData)
            check("eh", bean.earlyDataHeaderName ?: "", rs.earlyDataHeaderName)
            check("packet", bean.packetEncoding ?: 0, rs.packetEncoding)
            if (bean is io.nekohasekai.sagernet.fmt.trojan.TrojanBean) {
                check("password", bean.password, rs.password)
            } else if (bean is io.nekohasekai.sagernet.fmt.v2ray.VMessBean) {
                check("uuid", bean.uuid ?: "", rs.username)
                check("alterId", bean.alterId ?: 0, rs.alterId)
                check("encryption", bean.encryption ?: "", rs.encryption)
            }
        }
        assertTrue("${failures.size} differences across ${uris.size} cases:\n${failures.take(100).joinToString("\n")}", failures.isEmpty())
    }

    @Test fun duckSoftFieldInteractionMatrix() {
        val uris = mutableListOf<String>()
        for (scheme in listOf("trojan", "vless", "vmess")) {
            for (security in listOf("", "none", "tls", "reality", "unknown", "%20")) {
                for (type in listOf("tcp", "ws", "http", "h2", "httpupgrade", "grpc", "unknown")) {
                    for (early in listOf("eh=alone", "ed=0&eh=zero", "ed=-1&eh=negative", "ed=bad&eh=bad", "ed=2147483648", "ed&ed=2&eh=none")) {
                        uris += "$scheme://user@example.com:443/a/b/?security=$security&type=$type&$early&sni=%20&host=host.example&alpn=h2,%20http/1.1,,&allowInsecure=true&cert=C&pbk=P&sid=S&fp=chrome&path=%2Fquery&serviceName=svc&packetEncoding=xudp&flow=vision-udp443&encryption=none#name"
                    }
                }
            }
        }
        compareStandard(uris)
    }

    @Test fun urlAndDefaultPortMatrix() {
        compareStandard(listOf("trojan", "vless", "vmess").flatMap { scheme ->
            listOf("EXAMPLE.COM", "bücher.example", "faß.de", "[2001:0db8::1]", "example.com:", "example.com:443").flatMap { host ->
                listOf("", "/a/b/", "/a/../b/./", "/%2e/a/%2E%2e/b", "/a@b", "/%20", "//a//").map { path ->
                    "$scheme://user@$host$path?type=ws&security=tls#节点"
                }
            }
        })
    }

    @Test fun v2FlyProtocolGatingMatrix() {
        compareStandard(listOf("vmess", "vless").flatMap { scheme ->
            listOf("tcp", "ws", "http", "grpc", "httpupgrade", "unknown", "ws+tls", "tcp+tls").flatMap { type ->
                listOf("uuid-0", "uuid--1", "3", "%20", "uuid-bad").map { password ->
                    "$scheme://$type:$password@example.com?host=a%7Cb&path=%2Fp&serviceName=svc&tlsServerName=sni"
                }
            }
        })
    }

    @Test fun legacyJsonAndCsvMatrix() {
        fun b64(text: String) = "vmess://" + java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        val base = "\"add\":\"Example.COM\",\"port\":443,\"id\":\"uuid\",\"net\":\"ws\""
        val jsons = mutableListOf("{$base}")
        for (key in listOf("ps", "aid", "scy", "host", "path", "sni", "alpn", "fp", "net", "id", "add")) {
            for (value in listOf("null", "\"\"", "\" \"", "123", "true", "{}", "[]", "\"h2, http/1.1,,\"")) {
                jsons += "{$base,\"tls\":\"tls\",\"$key\":$value}"
            }
        }
        for (net in listOf("tcp", "h2", "grpc", "ws")) for (tls in listOf("none", "tls", "reality")) {
            jsons += "{$base,\"net\":\"$net\",\"tls\":\"$tls\",\"sni\":\" \",\"host\":\"host\",\"alpn\":\"h2, http/1.1,,\"}"
        }
        val csvs = listOf("obfs-path=bare", "obfs-path=\"/p\"", "Host:host", "Host:host[", "obfs-path=\"/p\"obfs Host:host[", "obfs=grpc", "over-tls=true,tls-host=sni")
            .map { "remarks = vmess,Example.COM,443,auto,\"uuid\",$it" }
        compareStandard((jsons + csvs).flatMap { listOf(b64(it), b64(it) + "?remarks=query") })
    }

    @Test fun tuicAndHysteriaMatrix() {
        val failures = mutableListOf<String>()
        var count = 0
        for (scheme in listOf("tuic", "hysteria", "hysteria2", "hy2")) {
            for (authority in listOf("user:pass@EXAMPLE.COM", "user:%20@bücher.example", "a%3Ab:c@[::1]:443", "example.com:")) {
                for (query in listOf("", "alpn=h2,%20http/1.1,,", "insecure=true&allow_insecure=true&disable_sni=true", "insecure=1&allow_insecure=1&disable_sni=1", "auth=%20&peer=%20&sni=%20", "upmbps=bad&downmbps=2147483648", "upmbps=-1&downmbps=+2", "protocol=UDP", "protocol=faketcp", "protocol=unknown", "mport=&obfs=GECKO&obfs-password=secret&obfsParam=secret", "congestion_control=&udp_relay_mode=", "auth=a&auth=b&sni=x&sni=y&alpn=&alpn=h3")) {
                    val uri = "$scheme://$authority?$query#node"
                    count++
                    val result = runCatching {
                        when (scheme) {
                            "tuic" -> io.nekohasekai.sagernet.fmt.tuic.parseTuic(uri)
                            "hysteria" -> io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1(uri)
                            else -> io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2(uri)
                        }
                    }
                    val rs = RustBridge.parseProxy(uri)
                    fun check(key: String, a: Any?, b: Any?) { if (a != b) failures += "$uri :: $key expected=<$a> actual=<$b>" }
                    check("success", result.isSuccess, rs.status == "SUCCESS")
                    if (result.isFailure || rs.status != "SUCCESS") continue
                    val kt = result.getOrThrow()
                    check("server", kt.serverAddress, rs.server)
                    check("port", kt.serverPort, rs.port)
                    check("name", kt.name ?: "", rs.name)
                    if (kt is io.nekohasekai.sagernet.fmt.tuic.TuicBean) {
                        check("uuid", kt.uuid ?: "", rs.username)
                        check("token", kt.token ?: "", rs.password)
                        check("sni", kt.sni ?: "", rs.sni)
                        check("alpn", kt.alpn ?: "", rs.alpn)
                        check("insecure", kt.allowInsecure ?: false, rs.allowInsecure)
                        check("disableSni", kt.disableSNI ?: false, rs.disableSNI)
                        check("cc", kt.congestionController ?: "cubic", rs.congestionControl)
                        check("relay", kt.udpRelayMode ?: "native", rs.udpRelayMode)
                    } else if (kt is io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean) {
                        check("auth", kt.authPayload, rs.authPayload)
                        check("ports", kt.serverPorts, rs.serverPorts)
                        check("sni", kt.sni, rs.sni)
                        check("alpn", kt.alpn, rs.alpn)
                        check("insecure", kt.allowInsecure, rs.allowInsecure)
                        check("up", kt.uploadMbps, rs.uploadMbps)
                        check("down", kt.downloadMbps, rs.downloadMbps)
                        check("obfs", kt.obfsType, rs.obfsType)
                        check("password", kt.obfuscation, rs.obfsPassword)
                    }
                }
            }
        }
        assertTrue("${failures.size} differences across $count cases:\n${failures.take(100).joinToString("\n")}", failures.isEmpty())
    }

    @Test fun legacyDecoderAndSyntaxBoundaries() {
        fun b64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        val uris = mutableListOf<String>()
        val json = "{\"add\":\"example.com\",\"port\":443,\"id\":\"uuid\",\"net\":\"ws\"}"
        for (variant in listOf(json.replace('"', '\''), json.replace("\"add\"", "add"), json.replace(",", ";"), json.replace(":", "="), json.replace("{", "{/*comment*/"), json.replace(",", ",,"), json.replace(",", " "), json.dropLast(1) + ",}", json + " trailing")) {
            uris += "vmess://" + b64(variant)
        }
        for (port in listOf("443", "0", "65536", "-1", "bad", "+443", "443:extra")) {
            val encoded = b64("auto:uuid@example.com:$port")
            for (payload in listOf(encoded, encoded.trimEnd('='), encoded + "===", encoded.chunked(8).joinToString("\n"), encoded.take(4) + "!" + encoded.drop(4))) {
                for (query in listOf("", "?remarks=hello#fragment", "?alterId=bad", "?obfs=websocket&tls=1&obfsParam=%7B'Host':'host'%7D")) {
                    uris += "vmess://$payload$query"
                }
            }
        }
        compareStandard(uris)
    }
}
