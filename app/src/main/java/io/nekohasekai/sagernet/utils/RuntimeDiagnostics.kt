package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.aidl.ISagerNetService
import libcore.Libcore

/** Session-only diagnostics: never persisted or silently resumed after a process restart. */
object RuntimeDiagnostics {
    const val LOG_CAPACITY_KIB = 256
    const val NORMAL_LOG_LEVEL = 2 // ConfigSnapshot's info level.
    fun setEnabled(service: ISagerNetService?, enabled: Boolean) {
        // The existing private service Binder gives synchronous acknowledgement, without
        // reconnecting the VPN. Do not enable the local session if the remote call fails.
        service?.setDiagnosticMode(enabled)
        Libcore.setDiagnosticMode(enabled)
    }

    fun remainingMillis(): Long = Libcore.diagnosticRemainingMillis()

    fun remainingMillis(service: ISagerNetService?): Long = maxOf(remainingMillis(),
        runCatching { service?.diagnosticRemainingMillis ?: 0L }.getOrDefault(0L))
}
