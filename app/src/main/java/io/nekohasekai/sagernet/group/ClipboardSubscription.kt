package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.core.CoreClient

/** Classifies a single web URL without fetching it or changing its query encoding. */
internal object ClipboardSubscription {
    fun find(text: String): String? {
        val value = text.trim()
        if (!Regex("(?i)^https?://").containsMatchIn(value) || value.any { it.isWhitespace() }) return null
        // HTTP(S) proxy endpoints are also valid node links. Keep their existing import path.
        val result = CoreClient.importProfiles(value, format = "links")
        if (result.profiles.isNotEmpty() && result.issues.none { it.severity != "warning" }) return null
        return SubscriptionLink.normalize(value)
    }
}
