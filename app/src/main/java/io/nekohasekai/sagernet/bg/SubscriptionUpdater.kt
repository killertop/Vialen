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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                                .filter { it.subscription?.autoUpdate == true }
                            Logs.d("subscription schedule: query complete, active=${subscriptions.size}")
                            val desired = subscriptions.associateBy(SubscriptionSchedule::name)
                            // Retire the old all-subscriptions worker, including queued/running work.
                            manager.cancelUniqueWork(WORK_NAME).get(15, TimeUnit.SECONDS)
                            val existing = manager.getWorkInfos(androidx.work.WorkQuery.fromTags(listOf(SubscriptionSchedule.TAG)))
                                .get(15, TimeUnit.SECONDS)
                            existing.filter { !it.state.isFinished && it.tags.none(desired::containsKey) }.forEach {
                                currentCoroutineContext().ensureActive()
                                manager.cancelWorkById(it.id).get(15, TimeUnit.SECONDS)
                            }
                            desired.forEach { (name, group) ->
                                currentCoroutineContext().ensureActive()
                                // UPDATE preserves the original enqueue time. Recomputing an
                                // initial delay for an unchanged request would shift its due time.
                                if (existing.any { !it.state.isFinished && name in it.tags }) return@forEach
                                manager.enqueueUniquePeriodicWork(name, UPDATE,
                                    SubscriptionSchedule.request(group, System.currentTimeMillis() / 1000))
                                    .get(15, TimeUnit.SECONDS)
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

        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val groupId = inputData.getLong(SubscriptionSchedule.ID, -1)
            if (groupId < 0) {
                // Old persisted workers can be instantiated after upgrade, but never download.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { reconfigureUpdaterOrThrow() }
                return@withContext Result.success()
            }
            val expected = inputData.getString(SubscriptionSchedule.CONFIG) ?: return@withContext Result.success()
            val notificationTag = SubscriptionRun.notificationTag(id)
            val outcome = SubscriptionRun.execute(groupId, expected,
                now = { System.currentTimeMillis() / 1000 }, connected = { DataStore.serviceState.connected },
                load = { SagerDatabase.groupDao.getById(it) },
                update = { profile, config -> GroupUpdater.executeUpdateResult(profile, false, config) },
                show = { profile ->
                    if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext,
                            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        notification.setContentText(applicationContext.getString(R.string.subscription_update_message, profile.displayName()))
                        nm.notify(notificationTag, 0, notification.build())
                    }
                }, clear = { nm.cancel(notificationTag, 0) })
            // Periodic success ends only this occurrence. Permanent failures are eligible
            // again next normal period, or immediately after a configuration/manual change.
            if (SubscriptionSchedule.retry(outcome, runAttemptCount)) Result.retry() else Result.success()
        }
    }

}
