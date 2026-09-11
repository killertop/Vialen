package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.preference.*
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.readableMessage

/** Keeps AndroidX preference persistence/restoration while sharing the app dialog surface. */
abstract class VialenPreferenceFragment : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fun normalize(group: PreferenceGroup) {
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.isIconSpaceReserved = false
                if (preference is EditTextPreference && preference.dialogLayoutResource in intArrayOf(0, androidx.preference.R.layout.preference_dialog_edittext)) {
                    preference.dialogLayoutResource = R.layout.layout_preference_input
                    if (preference.key == "serverAddress" && preference.text == "127.0.0.1") {
                        preference.dialogMessage = getString(R.string.ui_localhost_help)
                    }
                }
                if (preference is PreferenceGroup) normalize(preference)
            }
        }
        normalize(preferenceScreen)
        setDivider(null)
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") != null) return
        val fragment = when (preference) {
            is EditTextPreference -> VialenEditPreferenceDialog()
            is MultiSelectListPreference -> VialenMultiSelectPreferenceDialog()
            is ListPreference -> VialenListPreferenceDialog()
            else -> { super.onDisplayPreferenceDialog(preference); return }
        }
        fragment.arguments = Bundle().apply { putString("key", preference.key) }
        fragment.setTargetFragment(this, 0)
        fragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
    }
}

private fun Dialog.stylePreferenceSurface() {
    val density = context.resources.displayMetrics.density
    val shape = MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(20 * density).build())
    shape.fillColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vialen_surface))
    window?.setBackgroundDrawable(InsetDrawable(shape, (24 * density).toInt()))
}

class VialenEditPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {
    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        alert.stylePreferenceSurface()
        val input = alert.findViewById<EditText>(android.R.id.edit) ?: return
        if (preference.key == "serverAddress") {
            input.hint = getString(R.string.ui_address_hint)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            input.setSingleLine()
            input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        alert.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setText(R.string.ui_save)
            setOnClickListener {
                val pref = preference as EditTextPreference
                try {
                    val value = input.text.toString()
                    if (pref.callChangeListener(value)) {
                        pref.text = value
                        // Do not call the stock positive listener: it closes on invalid input too.
                        dismiss()
                    } else input.error = getString(R.string.ui_invalid_value)
                } catch (error: Exception) {
                    input.error = error.readableMessage
                }
            }
        }
    }
}

class VialenListPreferenceDialog : ListPreferenceDialogFragmentCompat() {
    override fun onStart() { super.onStart(); dialog?.stylePreferenceSurface() }
}

class VialenMultiSelectPreferenceDialog : MultiSelectListPreferenceDialogFragmentCompat() {
    override fun onStart() { super.onStart(); dialog?.stylePreferenceSurface() }
}
