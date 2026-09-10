package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) pending.finish()
        }
        val handler = Handler(Looper.getMainLooper())
        // Leave margin within the shortest broadcast budget (10 seconds). A coroutine
        // timeout alone cannot bound the updater's blocking Future.get(15 seconds).
        val work = runOnDefaultDispatcher {
            try {
                SubscriptionUpdater.reconfigureUpdater()
            } finally {
                finish()
            }
        }
        val deadline = Runnable {
            if (!finished.get()) {
                Logs.w("Subscription scheduling exceeded broadcast lifetime")
                work.cancel(CancellationException("Broadcast scheduling deadline exceeded"))
                finish()
            }
        }
        handler.postDelayed(deadline, 8_000)
        work.invokeOnCompletion { handler.removeCallbacks(deadline) }

        if (!DataStore.persistAcrossReboot) {   // sanity check
            enabled = false
            return
        }

        val doStart = when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> false // DataStore.directBootAware
            else -> SagerNet.user.isUserUnlocked
        } && DataStore.selectedProxy > 0

        if (doStart) SagerNet.startService()
    }
}
