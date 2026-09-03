package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun genReservedList(anyStr: String): List<Int>? {
    try {
        val list = anyStr.listByLineOrComma()
        if (list.size == 3) {
            val ints = list.mapNotNull {
                it.replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull()
            }
            if (ints.size == 3) return ints
        }
    } catch (_: Exception) {
    }
    return null
}

fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        if (bean.mtu > 0) mtu = bean.mtu
        peers = listOf(SingBoxOptions.WireGuardPeer().apply {
            address = bean.serverAddress
            port = bean.serverPort
            public_key = bean.peerPublicKey
            if (bean.peerPreSharedKey.isNotBlank()) pre_shared_key = bean.peerPreSharedKey
            allowed_ips = listOf("0.0.0.0/0", "::/0")
            if (bean.reserved.isNotBlank()) reserved = genReservedList(bean.reserved)
        })
    }
}
