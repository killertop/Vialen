package io.nekohasekai.sagernet.widget

import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.ThemedActivity

/**
 * @param activity ThemedActivity.
 * //@param view The view to find a parent from.
 * @param undo Callback for undoing removals.
 * @param commit Callback for committing removals.
 * @tparam T Item type.
 */
class UndoSnackbarManager<in T>(
    private val activity: ThemedActivity,
    private val callback: Interface<T>,
) {

    interface Interface<in T> {
        fun undo(actions: List<Pair<Int, T>>)
        fun commit(actions: List<Pair<Int, T>>)
    }

    private val recycleBin = ArrayList<Pair<Int, T>>()
    private var last: Snackbar? = null
    private var generation = 0L

    fun remove(items: Collection<Pair<Int, T>>) {
        recycleBin.addAll(items)
        val count = recycleBin.size
        val version = ++generation
        activity.snackbar(activity.resources.getQuantityString(R.plurals.removed, count, count))
            .apply {
                addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(bar: Snackbar?, event: Int) {
                        if (version == generation && last === bar && event != DISMISS_EVENT_ACTION) flush()
                    }
                })
                setAction(R.string.undo) {
                    if (version == generation && last === this) {
                        val actions = recycleBin.reversed()
                        invalidate()
                        callback.undo(actions)
                    }
                }
                last = this
                show()
            }
    }

    fun remove(vararg items: Pair<Int, T>) = remove(items.toList())

    /** Synchronously retire callbacks before dismiss can dispatch another event. */
    fun invalidate() {
        generation++
        recycleBin.clear()
        val previous = last
        last = null
        previous?.dismiss()
    }

    fun flush() {
        val actions = recycleBin.toList()
        invalidate()
        if (actions.isNotEmpty()) callback.commit(actions)
    }
}
