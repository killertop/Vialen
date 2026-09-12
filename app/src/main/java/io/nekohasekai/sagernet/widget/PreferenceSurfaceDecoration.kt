package io.nekohasekai.sagernet.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.R
import kotlin.math.roundToInt

/** Paints grouped surfaces underneath preferences, without changing row or touch geometry. */
class PreferenceSurfaceDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val inset = context.resources.getDimension(R.dimen.vialen_pearl_settings_inset)
    private val corner = context.resources.getDimension(R.dimen.vialen_pearl_corner)
    private val outline = ContextCompat.getColor(context, R.color.vialen_pearl_outline)
    private val surface = MaterialShapeDrawable().apply {
        fillColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.vialen_surface))
        setStroke(density, outline)
    }
    private val separator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = outline
        strokeWidth = density
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        // All spacing remains owned by the original preference layouts/divider decoration.
        outRect.setEmpty()
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
        fun isCategory(position: Int): Boolean = adapter.getItem(position) is PreferenceCategory
        val children = parent.children.mapNotNull { child ->
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || isCategory(position)) null else position to child
        }.sortedBy { it.first }.toList()
        var index = 0
        while (index < children.size) {
            val start = index
            while (index + 1 < children.size && children[index + 1].first == children[index].first + 1) index++
            val (firstPosition, first) = children[start]
            val (lastPosition, last) = children[index]
            val top = first.y
            val bottom = last.y + last.height
            val edges = groupEdges(firstPosition, lastPosition, adapter.itemCount, ::isCategory)
            surface.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(if (edges.first) corner else 0f)
                .setTopRightCornerSize(if (edges.first) corner else 0f)
                .setBottomLeftCornerSize(if (edges.second) corner else 0f)
                .setBottomRightCornerSize(if (edges.second) corner else 0f)
                .build()
            surface.setBounds(inset.roundToInt(), top.roundToInt(), (parent.width - inset).roundToInt(), bottom.roundToInt())
            surface.draw(canvas)
            for (row in start until index) {
                val child = children[row].second
                val y = child.y + child.height
                canvas.drawLine(16 * density, y, parent.width - 16 * density, y, separator)
            }
            index++
        }
    }

    companion object {
        /** Visible ends are rounded only at actual section boundaries, never at scroll edges. */
        @VisibleForTesting
        internal fun groupEdges(first: Int, last: Int, count: Int, isCategory: (Int) -> Boolean): Pair<Boolean, Boolean> =
            (first == 0 || isCategory(first - 1)) to (last == count - 1 || isCategory(last + 1))
    }
}
