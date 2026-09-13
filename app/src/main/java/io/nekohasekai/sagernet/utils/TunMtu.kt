package io.nekohasekai.sagernet.utils

/** Managed VPN MTU, shared by Android and the core; legacy preferences are ignored. */
object TunMtu {
    const val DEFAULT = 1500
    val range = 1280..65535
    fun requireValid(value: Int): Int {
        require(value in range) { "MTU 超出范围（1280–65535）" }
        return value
    }
}
