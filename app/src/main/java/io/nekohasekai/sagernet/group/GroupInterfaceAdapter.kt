package io.nekohasekai.sagernet.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.operationSucceeded
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(R.string.yes) { _, _ -> it.resume(true) }
                    .setNegativeButton(R.string.no) { _, _ -> it.resume(false) }
                    .setOnCancelListener { _ -> it.resume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        if (changed == 0 && duplicate.isEmpty()) {
            if (byUser) onMainDispatcher { if (!context.isFinishing && !context.isDestroyed) context.snackbar(
                    context.getString(
                            R.string.group_no_difference, group.displayName()
                    )
            ).operationSucceeded().show() }
        } else {
            var status = ""
            if (added.isNotEmpty()) {
                status += context.getString(
                        R.string.group_added, added.joinToString("\n", postfix = "\n\n")
                )
            }
            if (updated.isNotEmpty()) {
                status += context.getString(R.string.group_changed,
                        updated.map { it }.joinToString("\n", postfix = "\n\n") {
                            if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                        })
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                        R.string.group_deleted, deleted.joinToString("\n", postfix = "\n\n")
                )
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                        R.string.group_duplicate, duplicate.joinToString("\n", postfix = "\n\n")
                )
            }

            onMainDispatcher {
                if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
                val summary = context.getString(
                    R.string.subscription_update_summary,
                    group.displayName(), added.size, updated.size, deleted.size, duplicate.size
                )
                val dialog = MaterialAlertDialogBuilder(context)
                    .setIcon(R.drawable.ic_action_done)
                    .setTitle(R.string.subscription_update_complete)
                    .setMessage(summary)
                    .setPositiveButton(R.string.subscription_done, null)
                    .setNeutralButton(R.string.subscription_show_details, null)
                    .create()
                dialog.setOnShowListener {
                    var expanded = false
                    dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                        expanded = !expanded
                        dialog.setMessage(if (expanded) status.trim() else summary)
                        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setText(
                            if (expanded) R.string.subscription_hide_details else R.string.subscription_show_details
                        )
                    }
                }
                dialog.show()
            }

        }

    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            context.snackbar(message).show()
        }
    }

    override suspend fun alert(message: String) {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> it.resume(Unit) }
                    .setOnCancelListener { _ -> it.resume(Unit) }
                    .show()
            }
        }
    }

}
