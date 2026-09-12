package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.annotation.IdRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutNavigationDrawerBinding

/** A small, scrollable drawer sharing the main pages' pearl surfaces and text roles. */
class VialenNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val binding = LayoutNavigationDrawerBinding.inflate(LayoutInflater.from(context), this, true)
    private val items: List<MaterialButton> = listOf(
        binding.navConfiguration, binding.navGroup, binding.navRoute,
        binding.navSettings, binding.navAbout,
    )
    private val normalTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val selectedTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    var onItemSelected: ((Int) -> Unit)? = null
    var onCloseRequested: (() -> Unit)? = null

    @get:IdRes
    var checkedItemId: Int = R.id.nav_configuration
        private set

    init {
        setBackgroundResource(R.drawable.vialen_pearl_background)
        isFillViewport = true
        clipToPadding = false
        ViewCompat.setAccessibilityPaneTitle(this, context.getString(R.string.navigation_drawer_title))
        ViewCompat.setAccessibilityHeading(binding.navigationConnectionSection, true)
        ViewCompat.setAccessibilityHeading(binding.navigationAppSection, true)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(top = safe.top, bottom = safe.bottom)
            insets
        }
        items.forEach { item ->
            item.setOnClickListener { onItemSelected?.invoke(item.id) }
        }
        binding.navigationClose.setOnClickListener { onCloseRequested?.invoke() }
        setCheckedItem(checkedItemId)
    }

    fun setCheckedItem(@IdRes id: Int) {
        if (items.none { it.id == id }) return
        checkedItemId = id
        items.forEach { item ->
            item.isChecked = item.id == id
            item.typeface = if (item.isChecked) selectedTypeface else normalTypeface
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.vialen_navigation_width)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) maxWidth
        else minOf(maxWidth, MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
