package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<AbstractBean>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val response = Libcore.newHttpClient().apply {
                trySocks5(DataStore.mixedPort)
                tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) {
                    allowInsecure()
                }
                setURL(subscription.link)
                setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
            }.execute()
            proxies = parseRaw(Util.getStringBox(response.contentString))
                ?: error(app.getString(R.string.no_proxies_found))

            subscription.subscriptionUserinfo =
                Util.getStringBox(response.getHeader("Subscription-Userinfo"))

            // 修改默认名字
            if (proxyGroup.name?.startsWith("Subscription #") == true) {
                var remoteName = Util.getStringBox(response.getHeader("content-disposition"))
                if (remoteName.isNotBlank()) {
                    remoteName = Util.decodeFilename(remoteName)
                    if (remoteName.isNotBlank()) {
                        proxyGroup.name = remoteName
                    }
                }
            }
        }

        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                println("Exists name: $name")
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val duplicate = if (subscription.deduplication) {
            val dedup = SubscriptionDedup.apply(proxies)
            proxies = dedup.proxies
            dedup.duplicates
        } else emptyList()

        val previousTimestamp = subscription.lastUpdated
        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        val result = try {
            SubscriptionPersistence.apply(SagerDatabase.instance, proxyGroup, proxies)
        } catch (error: Throwable) {
            subscription.lastUpdated = previousTimestamp
            throw error
        }
        io.nekohasekai.sagernet.database.ProfileManager.selectFirstIfNeeded(proxyGroup.id)
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, result.changed, result.added, result.updated, result.deleted, duplicate, byUser
        )
    }

    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? =
        HybridRawSubscription.parse(text, fileName)

    fun clashCipher(cipher: String): String = if (cipher == "dummy") "none" else cipher

    fun parseWireGuard(conf: String): List<WireGuardBean> =
        checkNotNull(RustRawSubscription.parse(conf, mode = "wireguard")).map { it as WireGuardBean }

    fun parseJSON(json: Any): List<AbstractBean> =
        checkNotNull(RustRawSubscription.parse("", mode = "json", json = json))
}
