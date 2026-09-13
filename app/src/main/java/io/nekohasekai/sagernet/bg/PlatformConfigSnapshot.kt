package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.AppRoutingStore
import io.nekohasekai.sagernet.utils.AppRoutingConfig

/** Inputs consumed by Android's VPN builder rather than by the core compiler. */
internal data class PlatformConfigSnapshot(
    val serviceMode: String,
    val mtu: Int,
    val ipv6Mode: Int,
    val appRouting: AppRoutingConfig,
    val bypassLan: Boolean,
    val appendHttpProxy: Boolean,
    val mixedPort: Int,
    val metered: Boolean,
) {
    companion object {
        fun capture() = PlatformConfigSnapshot(DataStore.serviceMode, DataStore.mtu,
            DataStore.ipv6Mode, AppRoutingStore.read(),
            DataStore.bypassLan, DataStore.appendHttpProxy, DataStore.mixedPort,
            DataStore.meteredNetwork)
    }
}
