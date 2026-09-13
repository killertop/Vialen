package io.nekohasekai.sagernet.bg

import com.google.gson.JsonParser

internal object SelectorReloadPolicy {
    private fun normalize(config: String, tags: Set<String>): com.google.gson.JsonElement {
        val root = JsonParser.parseString(config).asJsonObject
        val selectors = root.getAsJsonArray("outbounds").filter {
            it.asJsonObject.get("type")?.asString == "selector" &&
                it.asJsonObject.get("tag")?.asString == "selected"
        }
        require(selectors.size == 1)
        val selector = selectors.single().asJsonObject
        require(selector.get("default")?.asString in tags)
        // Only the compiler-owned selection may differ. Every other JSON value remains checked.
        selector.remove("default")
        return root
    }

    fun canReuse(
        runningGroup: Long,
        candidateGroup: Long,
        runningConfig: String,
        candidateConfig: String,
        runningTags: Map<Long, String>,
        candidateTags: Map<Long, String>,
        selectedId: Long,
    ): Boolean = runningGroup >= 0 && runningGroup == candidateGroup &&
        (runningConfig == candidateConfig || runCatching {
            normalize(runningConfig, runningTags.values.toSet()) ==
                normalize(candidateConfig, candidateTags.values.toSet())
        }.getOrDefault(false)) && runningTags == candidateTags &&
        !runningTags[selectedId].isNullOrBlank()
}
