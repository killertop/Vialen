package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.core.CoreClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.utils.Util

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<Profile>
        var remoteUserinfo: String? = null
        var remoteGroupName: String? = null
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.use { it.readProfileText() }

            proxies = contentText?.let { parseRaw(contentText, showWarnings = byUser) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            val response = SubscriptionFetch.fetch(subscription.link, subscription.customUserAgent)
            proxies = parseRaw(response.text, showWarnings = byUser)
            remoteUserinfo = response.userinfo

            // 修改默认名字
            if (proxyGroup.name?.startsWith("Subscription #") == true) {
                var remoteName = response.disposition
                if (remoteName.isNotBlank()) {
                    remoteName = Util.decodeFilename(remoteName)
                    if (remoteName.isNotBlank()) {
                        remoteGroupName = remoteName
                    }
                }
            }
        }

        currentCoroutineContext().ensureActive()
        if (subscription.forceResolve) proxies = forceResolve(proxies, proxyGroup.id)

        val duplicate = if (subscription.deduplication) {
            val dedup = SubscriptionDedup.apply(proxies)
            proxies = dedup.proxies
            dedup.duplicates
        } else emptyList()

        currentCoroutineContext().ensureActive()
        val previousName = proxyGroup.name
        val previousUserinfo = subscription.subscriptionUserinfo
        val previousTimestamp = subscription.lastUpdated
        remoteGroupName?.let { proxyGroup.name = it }
        remoteUserinfo?.let { subscription.subscriptionUserinfo = it }
        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        val result = try {
            SubscriptionPersistence.apply(SagerDatabase.instance, proxyGroup, proxies)
        } catch (error: Throwable) {
            proxyGroup.name = previousName
            subscription.subscriptionUserinfo = previousUserinfo
            subscription.lastUpdated = previousTimestamp
            throw error
        }
        currentCoroutineContext().ensureActive()
        io.nekohasekai.sagernet.database.ProfileManager.selectFirstIfNeeded(proxyGroup.id)

        userInterface?.onUpdateSuccess(
            proxyGroup, result.changed, result.added, result.updated, result.deleted, duplicate, byUser
        )
    }

    suspend fun parseRaw(text: String, fileName: String = "", showWarnings: Boolean = true): List<Profile> {
        currentCoroutineContext().ensureActive()
        val result = CoreClient.importProfiles(text, fileName = fileName)
        val profiles = result.requireComplete()
        val warnings = result.issues.filter { it.severity == "warning" && it.code != "NON_PROXY_ENTRY" }
        if (warnings.isNotEmpty()) {
            val message = warnings.take(5).joinToString("\n") { "${it.code}: ${it.message}" }
            Logs.w(message)
            if (showWarnings) withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        currentCoroutineContext().ensureActive()
        return profiles
    }
}
