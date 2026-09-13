package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d("Runtime configuration built")
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        looper = service?.let { TrafficLooper(it.data) }
        looper?.start()
    }

    override fun close() {
        var failure: Throwable? = null
        try {
            super.close()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            runBlocking { looper?.stop() }
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
