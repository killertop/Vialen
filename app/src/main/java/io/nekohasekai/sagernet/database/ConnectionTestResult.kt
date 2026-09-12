package io.nekohasekai.sagernet.database

/** Only the fields owned by a connection test, detached from the profile snapshot. */
data class ConnectionTestResult(
    val id: Long,
    val status: Int,
    val ping: Int,
    val error: String?,
)
