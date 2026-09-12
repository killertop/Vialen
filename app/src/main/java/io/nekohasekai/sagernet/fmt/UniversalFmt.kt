package io.nekohasekai.sagernet.fmt

import android.net.Uri
import io.nekohasekai.sagernet.database.ProxyGroup

/** Shares only the subscription address and display name, without adding the separately stored token. */
fun ProxyGroup.toUniversalLink(): String {
    val source = requireNotNull(subscription) { "Group is not a subscription" }
    require(source.link.isNotBlank()) { "Subscription URL is empty" }
    return Uri.Builder()
        .scheme("sn")
        .authority("subscription")
        .appendQueryParameter("url", source.link)
        .appendQueryParameter("name", name.orEmpty())
        .build()
        .toString()
}
