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
