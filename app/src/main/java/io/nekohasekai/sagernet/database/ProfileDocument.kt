package io.nekohasekai.sagernet.database

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ProfileAdapter
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import moe.matsuri.nb4a.proxy.config.ConfigBean

/** Database payload. UI forms never define the persisted node schema. */
data class ProfileDocument(
    @SerializedName("kind") val kind: String,
    @SerializedName("profile") val profile: Profile? = null,
    @SerializedName("name") val name: String = "",
    @SerializedName("hops") val hops: List<Long> = emptyList(),
    @SerializedName("content") val content: String = "",
    @SerializedName("scope") val scope: String = "config",
) {
    fun entityType(): Int = when (kind) {
        "node" -> profileType(requireNotNull(profile))
        "chain" -> ProxyEntity.TYPE_CHAIN
        "raw_config" -> ProxyEntity.TYPE_CONFIG
        else -> error("Unknown profile document kind: $kind")
    }

    fun toBean(): AbstractBean = when (kind) {
        "node" -> ProfileAdapter.toBean(requireNotNull(profile))
        "chain" -> ChainBean().apply { initializeDefaultValues(); name = this@ProfileDocument.name; proxies = hops.toMutableList() }
        "raw_config" -> ConfigBean().apply { initializeDefaultValues(); name = this@ProfileDocument.name; config = content; type = if (scope == "outbound") 1 else 0 }
        else -> error("Unknown profile document kind: $kind")
    }

    companion object {
        private val gson = Gson()
        fun encode(value: ProfileDocument): String = gson.toJson(value)
        fun decode(value: String): ProfileDocument = requireNotNull(gson.fromJson(value, ProfileDocument::class.java)).also {
            it.entityType()
            require(it.kind != "raw_config" || it.scope in setOf("config", "outbound")) { "Invalid raw configuration scope" }
        }
        fun profileType(profile: Profile): Int = when (profile.type) {
            "socks" -> ProxyEntity.TYPE_SOCKS
            "http" -> ProxyEntity.TYPE_HTTP
            "shadowsocks" -> ProxyEntity.TYPE_SS
            "vmess", "vless" -> ProxyEntity.TYPE_VMESS
            "trojan" -> ProxyEntity.TYPE_TROJAN
            "hysteria2" -> ProxyEntity.TYPE_HYSTERIA
            "tuic" -> ProxyEntity.TYPE_TUIC
            "wireguard" -> ProxyEntity.TYPE_WG
            "shadowtls" -> ProxyEntity.TYPE_SHADOWTLS
            "anytls" -> ProxyEntity.TYPE_ANYTLS
            else -> error("Unsupported profile type: ${profile.type}")
        }
    }
}
