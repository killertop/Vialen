package io.nekohasekai.sagernet.utils

/** Shared Android VPN / core TUN limits. Never repair persisted values silently. */
object TunMtu {
    const val DEFAULT = 9000
    val range = 1280..65535
    fun requireValid(value: Int): Int {
        require(value in range) { "Invalid MTU $value: open Connection compatibility and choose 1280–65535." }
        return value
    }
}
