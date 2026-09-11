package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.os.Bundle
import android.text.InputFilter
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteRuleSet

/** Restorable draft: cancel never changes the route or writes to Room. */
class RouteRuleSetEditor : AppCompatDialogFragment() {
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var formatInput: Spinner

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val owner = requireContext()
        nameInput = EditText(owner).apply {
            hint = getString(R.string.route_set_name); setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(128)); setText(savedInstanceState?.getString("name").orEmpty())
        }
        urlInput = EditText(owner).apply {
            hint = getString(R.string.route_set_url); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            filters = arrayOf(InputFilter.LengthFilter(4096)); setText(savedInstanceState?.getString("url").orEmpty())
        }
        formatInput = Spinner(owner).apply {
            adapter = ArrayAdapter(owner, android.R.layout.simple_spinner_dropdown_item, listOf("SRS (.srs)", "JSON (.json)"))
            setSelection(savedInstanceState?.getInt("format") ?: 0)
        }
        val view = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(nameInput); addView(urlInput); addView(formatInput)
        }
        return MaterialAlertDialogBuilder(owner).setTitle(R.string.route_set_custom).setView(view)
            .setPositiveButton(R.string.ui_save, null).setNegativeButton(android.R.string.cancel, null).create()
    }

    override fun onStart() {
        super.onStart()
        (requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val ref = RouteRuleSet(nameInput.text.toString().trim(), urlInput.text.toString().trim(),
                    if (formatInput.selectedItemPosition == 0) "binary" else "source", requireArguments().getString("direction")!!).validate()
                DataStore.routeRuleSets = RouteRuleSet.encode(RouteRuleSet.decode(DataStore.routeRuleSets) + ref)
                (activity as? RouteSettingsActivity)?.updateRuleSetSummaries()
                dismiss()
            } catch (e: Exception) { urlInput.error = e.message }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::nameInput.isInitialized) {
            outState.putString("name", nameInput.text.toString())
            outState.putString("url", urlInput.text.toString())
            outState.putInt("format", formatInput.selectedItemPosition)
        }
    }
}
