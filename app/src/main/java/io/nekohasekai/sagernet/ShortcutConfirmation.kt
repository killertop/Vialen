package io.nekohasekai.sagernet

import android.app.Activity
import android.app.AlertDialog

/** Exported launcher activities have no authenticated startActivity caller identity.
 * Require a visible user gesture before any service or selection mutation. */
internal fun Activity.confirmShortcut(action: Int, message: String = getString(R.string.shortcut_confirm), proceed: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(action)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok) { _, _ -> proceed() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
        .setOnCancelListener { finish() }
        .show()
}
