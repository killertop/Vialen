// Frozen from 297a077; only package and object name changed. Test-only Rust oracle.
package io.nekohasekai.sagernet.oracle

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.rust.RustBridge
import moe.matsuri.nb4a.proxy.config.ConfigBean

/** Kotlin projects actual post-resolution Beans; Rust owns exact stable grouping. */
internal object FrozenV15SubscriptionDedup {
    data class Result(val proxies: List<AbstractBean>, val duplicates: List<String>)

    private fun key(bean: AbstractBean): String {
        val fields = if (bean is ConfigBean) listOf("config", bean.config) else
            listOf("endpoint", bean.javaClass.toString(), bean.serverAddress, bean.serverPort.toString())
        return buildString {
            fields.forEach { field ->
                // Preserve null distinctly from the literal string "null".
                if (field == null) append("-:") else append(field.length).append(':').append(field)
            }
        }
    }

    fun apply(proxies: List<AbstractBean>): Result {
        val ranks = RustBridge.rankDedupKeys(proxies.map(::key))
        val unique = ArrayList<AbstractBean>()
        val firstNames = ArrayList<String>()
        val duplicates = ArrayList<String>()
        proxies.forEachIndexed { index, bean ->
            val rank = ranks[index]
            if (rank == unique.size) {
                unique.add(bean)
                firstNames.add(bean.displayName())
            } else {
                val first = firstNames[rank].replace(" ($rank)", "")
                if (first.isNotBlank()) {
                    duplicates.add("$first ($rank)")
                    firstNames[rank] = ""
                }
                duplicates.add("${bean.displayName()} ($rank)")
            }
        }
        return Result(unique, duplicates)
    }
}
