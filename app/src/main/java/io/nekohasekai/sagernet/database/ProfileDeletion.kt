package io.nekohasekai.sagernet.database

/** A confirmed operation, not an entity snapshot to write back. */
data class ProfileDeletion(val id: Long, val groupId: Long, val unavailableDocument: String? = null)
