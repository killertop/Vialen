package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.core.Profile

/** Names and provider IDs are presentation/identity, all other fields are connection semantics. */
internal object SubscriptionDedup {
    data class Result(val proxies: List<Profile>, val duplicates: List<String>)
    fun semanticKey(profile: Profile): Profile = profile.copy(id = "", name = "")
    fun apply(proxies: List<Profile>): Result {
        val seen = HashSet<Profile>()
        val unique = ArrayList<Profile>()
        val duplicates = ArrayList<String>()
        proxies.forEach { profile ->
            if (seen.add(semanticKey(profile))) unique.add(profile)
            else duplicates.add(SubscriptionNames.display(profile))
        }
        return Result(unique, duplicates)
    }
}
