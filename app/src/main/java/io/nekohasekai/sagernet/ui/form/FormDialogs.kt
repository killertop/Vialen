package io.nekohasekai.sagernet.ui.form

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.readableMessage

fun Context.showFormError(error: Exception) {
    MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(error.readableMessage).setPositiveButton(android.R.string.ok, null).show()
}

/** Install the click handler after show: the default positive callback always dismisses. */
fun Context.showIntegerFormDialog(
    title: CharSequence,
    initial: String,
    range: IntRange,
    commit: (Int) -> Boolean,
): AlertDialog {
    val input = EditText(this).apply {
        inputType = EditorInfo.TYPE_CLASS_NUMBER
        setText(initial)
    }
    val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val number = validatedFormInt(input.text.toString(), range)
            if (number == null) {
                input.error = getString(R.string.form_integer_range, range.first, range.last)
                return@setOnClickListener
            }
            try {
                if (commit(number)) dialog.dismiss()
            } catch (e: Exception) {
                input.error = e.readableMessage
            }
        }
    }
    dialog.show()
    return dialog
}
