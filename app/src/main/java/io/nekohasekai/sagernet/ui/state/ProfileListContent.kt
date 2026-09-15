package io.nekohasekai.sagernet.ui.state

import io.nekohasekai.sagernet.database.ProxyEntity

/** Reuses the persisted document String; no Kryo byte array or boxed byte collection.
 * Document changes must invalidate click/share targets even when visible labels are unchanged.
 * Traffic is delivered separately and must not invalidate content or selection bindings. */
internal data class ProfileListContent(val document: String, val type: Int,
    val status: Int, val ping: Int, val error: String?) {
    companion object {
        fun capture(row: ProxyEntity) = ProfileListContent(row.document, row.type, row.status, row.ping, row.error)
    }
}
