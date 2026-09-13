package io.nekohasekai.sagernet.bg

internal object SelectorReloadPolicy {
    fun canReuse(
        runningGroup: Long,
        candidateGroup: Long,
        runningConfig: String,
        candidateConfig: String,
        runningTags: Map<Long, String>,
        candidateTags: Map<Long, String>,
        selectedId: Long,
    ): Boolean = runningGroup >= 0 && runningGroup == candidateGroup &&
        runningConfig == candidateConfig && runningTags == candidateTags &&
        !runningTags[selectedId].isNullOrBlank()
}
