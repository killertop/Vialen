package io.nekohasekai.sagernet.utils

/** One immutable routing decision, shared by the editor and VPN startup. */
data class AppRoutingConfig(
    val enabled: Boolean = false,
    val bypass: Boolean = true,
    val packages: Set<String> = emptySet(),
) {
    enum class Problem { ACCESS_UNAVAILABLE, EMPTY_SELECTION, MISSING_APPS }

    fun validate(availablePackages: Set<String>?, ownPackage: String): Problem? = when {
        !enabled -> null // Switching off must remain possible after permission revocation.
        availablePackages == null -> Problem.ACCESS_UNAVAILABLE
        !bypass && (packages - ownPackage).isEmpty() -> Problem.EMPTY_SELECTION
        !availablePackages.containsAll(packages - ownPackage) -> Problem.MISSING_APPS
        else -> null
    }

    companion object {
        fun parsePackages(value: String): Set<String> = value.lineSequence()
            .map(String::trim).filter(String::isNotEmpty).toSet()
    }
}
