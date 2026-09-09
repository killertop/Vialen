package io.nekohasekai.sagernet.bg

import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import android.widget.Toast
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    private val scheduling = Mutex()

    /** Group persistence has already succeeded at several callers. Report scheduling failures
     * separately rather than turning a saved group into an apparent failed save. */
    suspend fun reconfigureUpdater() {
        reportSchedulingFailure(operation = {
            reconfigureUpdaterOrThrow()
            NotificationManagerCompat.from(app).cancel("subscription-schedule", 0)
        }, report = { e ->
            Logs.e("Automatic subscription scheduling failed", e)
            onMainDispatcher {
                try {
                    val message = app.getString(R.string.subscription_schedule_failed)
                    if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                            app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        NotificationManagerCompat.from(app).notify("subscription-schedule", 0,
                            NotificationCompat.Builder(app, "service-subscription")
                                .setSmallIcon(R.drawable.ic_service_active)
                                .setContentTitle(app.getString(R.string.subscription_update))
                                .setContentText(message)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                                .setAutoCancel(true).build())
                    } else Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                } catch (feedbackError: Exception) {
                    Logs.e("Could not display subscription scheduling failure", feedbackError)
                }
            }
        })
    }

    /** Strict completion is used by upgrade verification; it must not accept a reported failure. */
    suspend fun reconfigureUpdaterOrThrow() {
        val completed = withTimeoutOrNull(30_000) {
            scheduling.withLock {
                withContext(Dispatchers.IO) {
                    // BootReceiver runs in :bg; a process-local Mutex alone cannot order it against
                    // edits in the UI process. Hold the file lock through the remote operation.
                    RandomAccessFile(File(app.filesDir, "subscription-schedule.lock"), "rw").channel.use { channel ->
                        val lock = withTimeoutOrNull(10_000) {
                            var acquired = channel.tryLock()
                            while (acquired == null) {
                                delay(25)
                                acquired = channel.tryLock()
                            }
                            acquired
                        } ?: throw TimeoutException("Subscription scheduling lock timed out")
                        lock.use {
                            Logs.d("subscription schedule: lock acquired")
                            Logs.d("subscription schedule: initialize manager")
                            val manager = RemoteWorkManager.getInstance(app)
                            Logs.d("subscription schedule: manager ready, query subscriptions")
                            val subscriptions = SagerDatabase.groupDao.subscriptions()
                                .filter { it.subscription!!.autoUpdate }
                            Logs.d("subscription schedule: query complete, active=${subscriptions.size}")
                            if (subscriptions.isEmpty()) {
                                manager.cancelUniqueWork(WORK_NAME).get(15, TimeUnit.SECONDS)
                            } else {
                                val now = System.currentTimeMillis() / 1000L
                                val intervals = subscriptions.map {
                                    it.subscription!!.autoUpdateDelay.toLong().coerceAtLeast(15)
                                }
                                val minDelay = intervals.minOrNull()!!
                                val initialDelay = subscriptions.zip(intervals).minOf { (group, interval) ->
                                    (group.subscription!!.lastUpdated.toLong() + interval * 60L - now).coerceAtLeast(0)
                                }
                                // UPDATE preserves identity/history. Cancel before UPDATE destroys both.
                                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                                    .setInitialDelay(initialDelay, TimeUnit.SECONDS).build()
                                manager.enqueueUniquePeriodicWork(WORK_NAME, UPDATE, request).get(15, TimeUnit.SECONDS)
                            }
                            Logs.d("subscription schedule: remote operation complete")
                        }
                    }
                }
            }
            true
        }
        if (completed == null) throw TimeoutException("Subscription scheduling timed out")
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            Logs.d("subscription worker process=${io.nekohasekai.sagernet.SagerNet.application.process}")
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            var failed = false
            try {
                if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                    val subscription = profile.subscription!!
                    if (System.currentTimeMillis() / 1000L - subscription.lastUpdated.toLong() <
                        subscription.autoUpdateDelay.toLong().coerceAtLeast(15) * 60L) {
                        Logs.d("work: not updating " + profile.displayName())
                        continue
                    }
                    Logs.d("work: updating " + profile.displayName())
                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                            applicationContext, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED) {
                        nm.notify(2, notification.build())
                    }
                    if (!GroupUpdater.executeUpdate(profile, false)) failed = true
                }

            } finally {
                nm.cancel(2)
            }
            return if (failed) Result.retry() else Result.success()
        }
    }

}