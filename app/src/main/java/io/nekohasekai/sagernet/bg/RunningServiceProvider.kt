package io.nekohasekai.sagernet.bg

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore

/** Read-only, same-UID IPC. Never persists a snapshot that could outlive the service process. */
class RunningServiceProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "snapshot")
        val service = DataStore.baseService
        val data = service?.data
        return Bundle().apply {
            if (data != null && data.state != BaseService.State.Stopped && data.state != BaseService.State.Idle) {
                putString("mode", if (service is VpnService) Key.MODE_VPN else Key.MODE_PROXY)
                data.proxy?.platformConfig?.let {
                    putInt("port", it.mixedPort)
                    putBoolean("metered", it.metered)
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal data class RunningServiceSnapshot(val serviceMode: String, val mixedPort: Int?, val metered: Boolean?) {
    companion object {
        fun read(context: Context): RunningServiceSnapshot? {
            val value = context.contentResolver.call(
                Uri.parse("content://${context.packageName}.running-service"), "snapshot", null, null
            ) ?: return null
            val mode = value.getString("mode") ?: return null
            return RunningServiceSnapshot(mode,
                if (value.containsKey("port")) value.getInt("port") else null,
                if (value.containsKey("metered")) value.getBoolean("metered") else null)
        }
    }
}
