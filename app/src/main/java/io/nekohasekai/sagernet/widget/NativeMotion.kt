package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R

/** Small, interruptible presentation effects; never schedules a business-state update. */
object NativeMotion {
    const val DURATION = 180L

    fun enabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f &&
            (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled())

    fun settle(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }

    fun text(view: TextView, value: CharSequence) {
        if (view.text.toString() == value.toString()) return
        settle(view)
        // Text/accessibility change synchronously, even when the view is hidden or motion is off.
        view.text = value
        if (value.isEmpty() || !view.isShown || !view.isAttachedToWindow || !enabled(view.context)) return
        if (view.getTag(R.id.native_motion_tracking) == null) {
            view.setTag(R.id.native_motion_tracking, true)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { settle(v) }
            })
        }
        view.alpha = 0.65f
        view.translationY = 3f * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(DURATION)
            .setInterpolator(DecelerateInterpolator()).start()
    }
}

/** Only call on a completed import/update, never for service connection or test results. */
fun Snackbar.operationSucceeded(): Snackbar = apply {
    animationMode = Snackbar.ANIMATION_MODE_FADE // Material owns entry/exit; no second animator.
    view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
        val check = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_action_done)?.mutate()
        check?.setTint(currentTextColor)
        setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null)
        compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    }
}
