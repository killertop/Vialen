package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs

/** Never equate a non-empty (potentially filtered) package query with authorization. */
object InstalledAppAccess {
    private const val OEM_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"
    data class Snapshot(val packages: Map<String, PackageInfo>? = null, val denied: Boolean = false)

    private fun isXiaomiPermission(context: Context): Boolean = try {
        context.packageManager.getPermissionInfo(OEM_PERMISSION, 0).packageName == "com.lbe.security.miui"
    } catch (_: PackageManager.NameNotFoundException) { false }

    @SuppressLint("SoonBlockedPrivateApi")
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES)
            != PackageManager.PERMISSION_GRANTED) return false
        return try {
            if (isXiaomiPermission(context)) {
                // HyperOS/MIUI exposes this through its app-op, not the runtime grant bit.
                // Same platform integration used by Telegram's XiaomiUtilities (OP_GET_INSTALLED_APPS).
                // Unknown results fail closed; a filtered list must not activate a VPN allowlist.
                val manager = context.getSystemService(AppOpsManager::class.java)
                val mode = AppOpsManager::class.java.getMethod("checkOpNoThrow",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                    .invoke(manager, 10022, Process.myUid(), context.packageName) as Int
                mode == AppOpsManager.MODE_ALLOWED
            } else {
                val permissionExists = try {
                    context.packageManager.getPermissionInfo(OEM_PERMISSION, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) { false }
                !permissionExists || context.checkSelfPermission(OEM_PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
        } catch (error: Exception) {
            Logs.w(error)
            false
        }
    }

    fun read(context: Context): Snapshot {
        if (!isGranted(context)) return Snapshot(denied = true)
        return try {
            val packages = context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { info ->
                    info.packageName != context.packageName &&
                        info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 } == true &&
                        (info.packageName == "android" ||
                            info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true)
                }.associateBy { it.packageName }
            // Recheck after the query: permission can be revoked while a request is in flight.
            if (!isGranted(context)) Snapshot(denied = true)
            else if (packages.isEmpty()) Snapshot()
            else Snapshot(packages)
        } catch (_: SecurityException) { Snapshot(denied = true) }
        catch (error: Exception) { Logs.w(error); Snapshot() }
    }

    fun settingsIntent(context: Context): Intent {
        if (isXiaomiPermission(context)) {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
                .setPackage("com.miui.securitycenter")
                .putExtra("extra_pkgname", context.packageName)
                .putExtra("extra_package_uid", Process.myUid())
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
    }

    fun problemMessage(problem: AppRoutingConfig.Problem): Int = when (problem) {
        AppRoutingConfig.Problem.ACCESS_UNAVAILABLE -> R.string.app_routing_access_required
        AppRoutingConfig.Problem.EMPTY_SELECTION -> R.string.app_routing_empty_selection
        AppRoutingConfig.Problem.MISSING_APPS -> R.string.app_routing_missing_apps
    }
}
