package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.utils.AppRoutingConfig
import java.util.concurrent.Callable

object AppRoutingStore {
    fun read(): AppRoutingConfig = PublicDatabase.instance.runInTransaction(Callable {
        val dao = PublicDatabase.kvPairDao
        AppRoutingConfig(dao[Key.PROXY_APPS]?.boolean ?: false,
            dao[Key.BYPASS_MODE]?.boolean ?: true,
            AppRoutingConfig.parsePackages(dao[Key.INDIVIDUAL]?.string.orEmpty()))
    })

    /** Observers are notified only after the complete decision is committed. */
    fun save(config: AppRoutingConfig, expected: AppRoutingConfig? = null): Boolean {
        val saved = PublicDatabase.instance.runInTransaction(Callable {
            if (expected != null && read() != expected) return@Callable false
            val dao = PublicDatabase.kvPairDao
            dao.put(KeyValuePair(Key.PROXY_APPS).put(config.enabled))
            dao.put(KeyValuePair(Key.BYPASS_MODE).put(config.bypass))
            dao.put(KeyValuePair(Key.INDIVIDUAL).put(config.packages.sorted().joinToString("\n")))
            true
        })
        if (!saved) return false
        DataStore.dirty = true
        DataStore.configurationStore.notifyCommittedChange(Key.PROXY_APPS)
        return true
    }
}
