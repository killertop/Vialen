package io.nekohasekai.sagernet.bg

import androidx.work.*
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.SubscriptionOutcome
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object SubscriptionSchedule {
    const val TAG = "SubscriptionUpdater.v3"
    const val LEGACY = "SubscriptionUpdater"
    const val ID = "group"
    const val CONFIG = "config"
    // Three total attempts in one periodic run; WorkManager owns exponential backoff.
    const val MAX_ATTEMPTS = 3
    fun document(bean: SubscriptionBean) = bean.link.startsWith("content://")
    fun fingerprint(bean: SubscriptionBean): String {
        // Length-delimited JSON avoids collisions; remote-owned fields are excluded.
        val json = com.google.gson.Gson().toJson(listOf(bean.type, bean.link, bean.forceResolve,
            bean.deduplication, bean.updateWhenConnectedOnly, bean.customUserAgent,
            bean.autoUpdate, bean.autoUpdateDelay))
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun name(group: ProxyGroup) = "$TAG:${group.id}:${fingerprint(group.subscription!!)}"
    fun due(bean: SubscriptionBean, connected: Boolean, nowSeconds: Long): Boolean =
        bean.autoUpdate && (!bean.updateWhenConnectedOnly || connected) &&
            nowSeconds - bean.lastUpdated.toLong() >= bean.autoUpdateDelay.toLong().coerceAtLeast(15) * 60

    fun request(group: ProxyGroup, nowSeconds: Long): PeriodicWorkRequest {
        val bean = group.subscription!!
        val minutes = bean.autoUpdateDelay.toLong().coerceAtLeast(15)
        return PeriodicWorkRequest.Builder(SubscriptionUpdater.UpdateTask::class.java, minutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(ID to group.id, CONFIG to fingerprint(bean)))
            .addTag(TAG).addTag(name(group))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(
                if (document(bean)) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
            .setInitialDelay((bean.lastUpdated.toLong() + minutes * 60 - nowSeconds).coerceAtLeast(0), TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
    fun retry(outcome: SubscriptionOutcome, attempt: Int) =
        outcome == SubscriptionOutcome.TEMPORARY_FAILURE && attempt < MAX_ATTEMPTS - 1
}
