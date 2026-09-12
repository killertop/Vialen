package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.core.Profile

internal object SubscriptionNames {
    fun display(profile: Profile): String = profile.name.ifBlank {
        val host = if (':' in profile.server) "[${profile.server}]" else profile.server
        "$host:${profile.port}"
    }

    /** Unique report labels only; subscription names remain unchanged in storage. */
    fun unique(names: List<String>): List<String> {
        val reserved = names.toHashSet()
        val used = HashSet<String>()
        val next = HashMap<String, Int>()
        return names.map { original ->
            if (used.add(original)) original else {
                var suffix = next[original] ?: 2
                var candidate: String
                do { candidate = "$original (${suffix++})" } while (candidate in reserved || candidate in used)
                next[original] = suffix
                used.add(candidate)
                candidate
            }
        }
    }
}
