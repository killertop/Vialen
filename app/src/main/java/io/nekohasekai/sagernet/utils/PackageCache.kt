package io.nekohasekai.sagernet.utils

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

object PackageCache {
    private val registered = AtomicBoolean(false)
    private val loader = SnapshotLoader(::readPackages)
    private val refreshes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Derived labels are separate from the immutable package indexes and versioned together.
    private var labelSnapshot: PackageSnapshot? = null
    private val labels = HashMap<String, String>()

    init { scope.launch { for (ignored in refreshes) reload() } }

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        try {
            // Register before the initial scan so changes during that scan cannot be missed.
            app.listenForPackageChanges(false) { refreshes.trySend(Unit) }
        } catch (error: Exception) {
            registered.set(false) // A later caller can retry listener registration.
            Logs.w(error)
        } finally { reload() } // Initial waiters get a terminal result even if registration fails.
    }

    fun reload(): Boolean = loader.refresh().also { success ->
        if (!success) loader.failure?.let { Logs.w(it) }
    }

    @SuppressLint("InlinedApi")
    private fun readPackages(): PackageSnapshot {
        val packages = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_PERMISSIONS or
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
        val apps = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        check(apps.isNotEmpty()) { "应用列表不可用，请检查权限后重试" }
        return PackageSnapshot(packages, apps)
    }

    /** Capture once per multi-package operation, including an entire configuration build. */
    fun snapshot(): PackageSnapshot {
        if (!registered.get()) register()
        return loader.await()
    }

    fun awaitLoadSync() { snapshot() }
    fun currentOrNull(): PackageSnapshot? = loader.currentOrNull()
    val installedPackages get() = snapshot().installedPackages

    @Synchronized fun loadLabel(packageName: String): String {
        val snapshot = loader.currentOrNull() ?: return packageName // Display-only fallback, never a routing UID.
        if (labelSnapshot !== snapshot) { labels.clear(); labelSnapshot = snapshot }
        return labels.getOrPut(packageName) {
            snapshot.application(packageName)?.loadLabel(app.packageManager)?.toString() ?: packageName
        }
    }
}
