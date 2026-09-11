package io.nekohasekai.sagernet.group

/** Preserves legacy replacement order while resuming long collision chains. */
internal object SubscriptionNames {
    fun unique(names: List<String>): List<String> {
        val used = HashSet<String>()
        var resume: HashMap<String, Pair<String, Int>>? = null
        return names.map { original ->
            val previous = resume?.get(original)
            var name = previous?.first ?: original
            var index = previous?.second ?: 0
            while (name in used) {
                name = name.replace(" ($index)", "") + " (${++index})"
            }
            used.add(name)
            // Unique and paired names do not need a second map.
            if (index >= 4) {
                val cache = resume ?: HashMap<String, Pair<String, Int>>().also { resume = it }
                cache[original] = name to index
            }
            name
        }
    }
}
