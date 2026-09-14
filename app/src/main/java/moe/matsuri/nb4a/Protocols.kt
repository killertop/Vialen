package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr

// Settings for all protocols, built-in or plugin
object Protocols {

    /** Only ordinary nodes have a standalone connection identity. Keep chains/raw configs. */
    fun deduplicationKey(entity: io.nekohasekai.sagernet.database.ProxyEntity): io.nekohasekai.sagernet.core.Profile? {
        val document = io.nekohasekai.sagernet.database.ProfileDocument.decode(entity.document)
        return document.profile?.takeIf { document.kind == "node" }?.let {
            io.nekohasekai.sagernet.group.SubscriptionDedup.semanticKey(it)
        }
    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return getColorAttr(R.attr.accentOrTextSecondary)
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> io.nekohasekai.sagernet.utils.UserFacingError.describe(msg)
        }
    }

}