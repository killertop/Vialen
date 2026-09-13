package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int {
        val session = Libcore.newUrlTestSession()
        return runCancellableUrlTest(
            cancel = { session.cancel() },
            initialize = { init() },
            start = { launch() },
            test = { session.run(box, link, timeout) },
            close = { close() },
        )
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
