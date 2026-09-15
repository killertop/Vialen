package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.PlatformConfigSnapshot
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    @Volatile internal var platformConfig: PlatformConfigSnapshot? = null
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    @Volatile var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        platformConfig = PlatformConfigSnapshot.capture().let { platform ->
            config.tunMtu?.let { platform.copy(mtu = it) } ?: platform
        }
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d("Runtime configuration built")
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    /**
     * Build and construct a temporary native instance before a running service is
     * stopped. The Kotlin compiler catches graph errors, while the native
     * constructor catches sing-box option errors (including full raw configs).
     * The temporary instance is never started and is always closed before return.
     */
    suspend fun buildConfigTmpAndValidate() = withContext(Dispatchers.IO) {
        buildConfigTmp()
        val candidate = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
        try {
            // Construction is the semantic validation step. Do not start this
            // instance: a candidate must not open a TUN or bind a listener.
        } finally {
            candidate.close()
        }
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        looper = service?.let { TrafficLooper(it.data) }
        looper?.start()
    }

    override fun close() = runBlocking { closeAndAwait() }

    suspend fun closeAndAwait() {
        var failure: Throwable? = null
        try {
            super.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            looper?.stop()
        } catch (error: Throwable) {
            val previous = failure
            if (previous == null) failure = error
            else if (previous !== error) {
                if (error is CancellationException && previous !is CancellationException) {
                    error.addSuppressed(previous)
                    failure = error
                } else previous.addSuppressed(error)
            }
        } finally {
            looper = null
        }
        failure?.let { throw it }
    }
}
