package io.nekohasekai.sagernet.utils

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Parcel
import android.os.Parcelable
import java.util.Collections

/** Process-local immutable indexes. Android's mutable metadata never escapes without a copy. */
class PackageSnapshot(packages: List<PackageInfo>, apps: List<ApplicationInfo>) {
    private val packageInfo = immutableMap(packages.filter {
        it.packageName == "android" || it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
    }.associate { it.packageName to copy(it, PackageInfo.CREATOR) })
    private val applicationInfo = immutableMap(apps.associate { it.packageName to copy(it, ApplicationInfo.CREATOR) })
    val packageMap: Map<String, Int> = immutableMap(applicationInfo.mapValues { it.value.uid })
    val uidMap: Map<Int, Set<String>> = immutableMap(applicationInfo.values.groupBy { it.uid }
        .mapValues { (_, values) -> Collections.unmodifiableSet(values.mapTo(linkedSetOf()) { it.packageName }) })
    val installedPackages: Map<String, PackageInfo>
        get() = immutableMap(packageInfo.mapValues { copy(it.value, PackageInfo.CREATOR) })
    fun application(packageName: String): ApplicationInfo? = applicationInfo[packageName]?.let { copy(it, ApplicationInfo.CREATOR) }

    companion object {
        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
        private fun <T : Parcelable> copy(value: T, creator: Parcelable.Creator<T>): T {
            val parcel = Parcel.obtain()
            try {
                value.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return creator.createFromParcel(parcel)
            } finally { parcel.recycle() }
        }
    }
}
