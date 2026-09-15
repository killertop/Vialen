package io.nekohasekai.sagernet.group

import androidx.room.withTransaction
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase

/** SQLite, not an in-process mutex, decides which download may commit. */
internal object SubscriptionRefresh {
    data class Ticket(val group: ProxyGroup, val configVersion: Long, val generation: Long)
    class Stale : IllegalStateException("订阅已修改或已有新刷新，请重试")

    suspend fun begin(db: SagerDatabase, id: Long, requireAutoUpdate: Boolean = false,
        expectedConfig: String? = null): Ticket = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        // Installed before any request leaves this transaction. The trigger also covers
        // other processes and direct DAO writers; no editor can bypass its revision.
        sql.execSQL("""CREATE TRIGGER IF NOT EXISTS subscription_config_changed
            AFTER UPDATE OF name, type, subscription, ungrouped, userOrder, `order`, isSelector, frontProxy, landingProxy ON proxy_groups
            WHEN OLD.name IS NOT NEW.name OR OLD.type IS NOT NEW.type OR OLD.subscription IS NOT NEW.subscription
              OR OLD.ungrouped IS NOT NEW.ungrouped OR OLD.userOrder IS NOT NEW.userOrder OR OLD.`order` IS NOT NEW.`order`
              OR OLD.isSelector IS NOT NEW.isSelector OR OLD.frontProxy IS NOT NEW.frontProxy OR OLD.landingProxy IS NOT NEW.landingProxy
            BEGIN UPDATE subscription_refresh_state SET configVersion = configVersion + 1 WHERE groupId = NEW.id; END""")
        val group = db.groupDao().getById(id)?.takeIf { it.type == GroupType.SUBSCRIPTION }
            ?: throw Stale()
        if (requireAutoUpdate && group.subscription?.autoUpdate != true) throw Stale()
        // An obsolete queued worker must not advance the generation of a valid newer refresh.
        if (expectedConfig != null && group.subscription?.let {
                io.nekohasekai.sagernet.bg.SubscriptionSchedule.fingerprint(it)
            } != expectedConfig) throw Stale()
        sql.execSQL("INSERT OR IGNORE INTO subscription_refresh_state(groupId, configVersion, generation) VALUES (?, 0, 0)", arrayOf(id))
        sql.execSQL("UPDATE subscription_refresh_state SET generation = generation + 1 WHERE groupId = ?", arrayOf(id))
        sql.query("SELECT configVersion, generation FROM subscription_refresh_state WHERE groupId = ?", arrayOf(id)).use {
            check(it.moveToFirst())
            Ticket(group, it.getLong(0), it.getLong(1))
        }
    }

    /** Must be called inside the same write transaction as profile/metadata changes. */
    fun requireCurrent(db: SagerDatabase, ticket: Ticket): ProxyGroup {
        check(db.inTransaction())
        db.openHelper.writableDatabase.query(
            "SELECT configVersion, generation FROM subscription_refresh_state WHERE groupId = ?", arrayOf(ticket.group.id)
        ).use {
            if (!it.moveToFirst() || it.getLong(0) != ticket.configVersion || it.getLong(1) != ticket.generation) throw Stale()
        }
        return db.groupDao().getById(ticket.group.id)?.takeIf { it.type == GroupType.SUBSCRIPTION } ?: throw Stale()
    }
}
