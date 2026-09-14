package io.nekohasekai.sagernet.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Kept outside ProxyGroup so a stale whole-row editor cannot rewind generations. */
@Entity(tableName = "subscription_refresh_state", foreignKeys = [ForeignKey(
    entity = ProxyGroup::class, parentColumns = ["id"], childColumns = ["groupId"],
    onDelete = ForeignKey.CASCADE,
)])
data class SubscriptionRefreshState(
    @PrimaryKey val groupId: Long,
    val configVersion: Long = 0,
    val generation: Long = 0,
)
