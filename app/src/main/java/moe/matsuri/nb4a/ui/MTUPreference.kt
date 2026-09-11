package moe.matsuri.nb4a.ui

import io.nekohasekai.sagernet.ui.form.showIntegerFormDialog
import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R

class MTUPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : ListPreference(context, attrs, defStyle, 0) {

    init {
        setSummaryProvider {
            value.toString()
        }
        dialogLayoutResource = R.layout.layout_mtu_help
    }

    override fun onClick() {
        val choices = entries.map { it.toString() } + context.getString(R.string.ui_custom)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(choices.toTypedArray(), entryValues.indexOf(value).takeIf { it >= 0 } ?: entryValues.size) { dialog, index ->
                if (index == entryValues.size) {
                    dialog.dismiss()
                    showCustomDialog()
                } else {
                    val proposed = entryValues[index].toString()
                    if (callChangeListener(proposed)) { value = proposed; dialog.dismiss() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    internal fun showCustomDialog() =
        context.showIntegerFormDialog("MTU", value.orEmpty(), 1000..10000) { mtu ->
            val proposed = mtu.toString()
            if (callChangeListener(proposed)) {
                value = proposed
                true
            } else false
        }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val itemView: View = holder.itemView
        itemView.setOnLongClickListener {
            showCustomDialog()
            true
        }
    }

}
