package io.nekohasekai.sagernet.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.nekohasekai.sagernet.R

enum class UrlTestPhase { PREPARING, RUNNING, STOPPING, FINISHED, STOPPED, ERROR }

data class UrlTestDialogState(
    val total: Int = 0,
    val completed: Int = 0,
    val available: Int = 0,
    val failed: Int = 0,
    val lastName: String? = null,
    val lastProtocol: String? = null,
    val lastResult: String? = null,
    val lastSuccess: Boolean? = null,
    val elapsedMillis: Long = 0,
    val phase: UrlTestPhase = UrlTestPhase.PREPARING,
    val error: String? = null,
)

/** A view of the caller-owned test session. All methods must run on the main thread. */
@MainThread
class UrlTestDialog(
    private val context: Context,
    groupName: String,
    private val onStop: () -> Unit,
    private val onBackground: () -> Unit,
) {
    private val builder = MaterialAlertDialogBuilder(context)
    private val content = LayoutInflater.from(builder.context)
        .inflate(R.layout.layout_url_test_dialog, FrameLayout(builder.context), false)
    private val phase: TextView = content.findViewById(R.id.url_test_phase)
    private val progress: LinearProgressIndicator = content.findViewById(R.id.url_test_progress)
    private val counts: TextView = content.findViewById(R.id.url_test_counts)
    private val statistics: TextView = content.findViewById(R.id.url_test_statistics)
    private val name: TextView = content.findViewById(R.id.url_test_name)
    private val result: TextView = content.findViewById(R.id.url_test_result)
    private val elapsed: TextView = content.findViewById(R.id.url_test_elapsed)
    private var state = UrlTestDialogState()
    private val dialog = builder
        .setTitle(R.string.url_test_dialog_title)
        .setView(content)
        .setCancelable(false)
        .setNegativeButton(R.string.url_test_stop, null)
        .setPositiveButton(R.string.url_test_background, null)
        .create()

    init {
        content.findViewById<TextView>(R.id.url_test_group).text = groupName
        render(state)
    }

    fun show() {
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            if (state.phase == UrlTestPhase.PREPARING || state.phase == UrlTestPhase.RUNNING) {
                render(state.copy(phase = UrlTestPhase.STOPPING))
                onStop()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (state.phase.isTerminal()) dismiss()
            else if (state.phase != UrlTestPhase.STOPPING) onBackground()
        }
        val minimumHeight = (48 * context.resources.displayMetrics.density).toInt()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).minHeight = minimumHeight
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).minHeight = minimumHeight
        render(state)
    }

    fun render(state: UrlTestDialogState) {
        this.state = state
        val terminal = state.phase.isTerminal()
        phase.setText(when (state.phase) {
            UrlTestPhase.PREPARING -> R.string.url_test_preparing
            UrlTestPhase.RUNNING -> R.string.url_test_running
            UrlTestPhase.STOPPING -> R.string.url_test_stopping
            UrlTestPhase.FINISHED -> R.string.url_test_finished
            UrlTestPhase.STOPPED -> R.string.url_test_stopped
            UrlTestPhase.ERROR -> R.string.url_test_error
        })
        val total = state.total.coerceAtLeast(0)
        val completed = state.completed.coerceIn(0, total)
        progress.max = total.coerceAtLeast(1)
        progress.setProgressCompat(completed, false)
        counts.text = context.getString(R.string.url_test_counts, completed, total)
        statistics.text = context.getString(R.string.url_test_statistics, state.available, state.failed)
        name.text = state.lastName ?: context.getString(
            if (terminal) R.string.url_test_no_result else R.string.url_test_waiting
        )
        val fallback = when (state.lastSuccess) {
            true -> context.getString(R.string.url_test_available)
            false -> context.getString(R.string.url_test_failed)
            null -> null
        }
        result.text = if (state.phase == UrlTestPhase.ERROR && !state.error.isNullOrBlank()) {
            state.error
        } else {
            listOfNotNull(state.lastProtocol?.takeIf { it.isNotBlank() }, state.lastResult ?: fallback)
                .joinToString(" · ")
                .ifBlank { if (terminal) "" else context.getString(R.string.url_test_waiting_detail) }
        }
        result.setTextColor(ContextCompat.getColor(context, when {
            state.phase == UrlTestPhase.ERROR -> R.color.vialen_error
            state.lastSuccess == true -> R.color.vialen_success
            state.lastSuccess == false -> R.color.vialen_error
            else -> R.color.vialen_text_secondary
        }))
        elapsed.text = context.getString(R.string.url_test_elapsed, state.elapsedMillis.coerceAtLeast(0) / 1000)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            visibility = if (terminal) View.GONE else View.VISIBLE
            isEnabled = state.phase != UrlTestPhase.STOPPING
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setText(if (terminal) R.string.url_test_done else R.string.url_test_background)
            isEnabled = state.phase != UrlTestPhase.STOPPING
        }
    }

    fun dismiss() = dialog.dismiss()

    private fun UrlTestPhase.isTerminal() =
        this == UrlTestPhase.FINISHED || this == UrlTestPhase.STOPPED || this == UrlTestPhase.ERROR
}
