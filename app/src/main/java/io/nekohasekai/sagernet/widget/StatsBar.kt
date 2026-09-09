package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    private var renderedState = BaseService.State.Idle
    private var renderVersion = 0L
    private var testVersion = 0L
    private var visibilityJob: Job? = null
    private var testJob: Job? = null

    var allowShow = true
        set(value) {
            field = value
            syncVisibility()
        }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow && renderedState.connected }
        return behavior
    }

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar, animate: Boolean) {
            if (!getAllowShow()) return
            super.slideUp(child, animate)
        }

        override fun slideDown(child: BottomAppBar, animate: Boolean) {
            if (getAllowShow()) {
                // Preserve Material's touch-exploration policy for ordinary scroll hiding.
                super.slideDown(child, animate)
            } else {
                // BottomAppBar.performHide calls this two-argument overload directly.
                // A page/state change is mandatory visibility, not a scroll gesture.
                val previous = isDisabledOnTouchExploration
                disableOnTouchExploration(false)
                try { super.slideDown(child, animate) }
                finally { disableOnTouchExploration(previous) }
            }
        }

    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val changed = renderedState != state
        renderedState = state
        if (changed) invalidateConnectionTest()
        hideOnScroll = state == BaseService.State.Connected
        if (state != BaseService.State.Connected) updateSpeed(0, 0)
        if (testJob?.isActive != true) showConnectionState()
        syncVisibility()
    }

    private fun showConnectionState() {
        if (!::statusText.isInitialized) return
        setStatus(context.getText(when (renderedState) {
            BaseService.State.Connected -> R.string.vpn_connected
            BaseService.State.Connecting -> R.string.connecting
            BaseService.State.Stopping -> R.string.stopping
            else -> R.string.not_connected
        }))
    }

    private fun syncVisibility() {
        val version = ++renderVersion
        visibilityJob?.cancel()
        if (!allowShow || renderedState != BaseService.State.Connected) {
            performHide()
            return
        }
        val activity = context as MainActivity
        visibilityJob = activity.lifecycleScope.launch {
            delay(100L)
            activity.lifecycle.withStarted {
                if (version == renderVersion && allowShow && renderedState.connected) performShow()
            }
        }
    }

    fun invalidateConnectionTest() {
        ++testVersion
        testJob?.cancel()
        testJob = null
        isEnabled = true
        showConnectionState()
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as MainActivity
        if (!renderedState.connected || !DataStore.serviceState.connected || testJob?.isActive == true) return
        val service = activity.connection.service ?: return
        val request = ++testVersion
        val profile = DataStore.currentProfile
        val selected = DataStore.selectedProxy
        val testUrl = DataStore.connectionTestURL
        fun isCurrent() = request == testVersion && renderedState.connected &&
            DataStore.serviceState.connected && activity.connection.service === service &&
            DataStore.currentProfile == profile && DataStore.selectedProxy == selected &&
            DataStore.connectionTestURL == testUrl
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        testJob = activity.lifecycleScope.launch {
            try {
                val elapsed = withContext(Dispatchers.IO) { service.urlTest() }
                if (isCurrent()) {
                    setStatus(
                        app.getString(
                            if (testUrl.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e.toString())
                if (isCurrent()) {
                    setStatus(app.getText(R.string.connection_test_failed))
                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).setAction(R.string.connection_test_retry) { testConnection() }.show()
                }
            } finally {
                if (request == testVersion) {
                    isEnabled = true
                    if (!isCurrent()) showConnectionState()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        ++renderVersion
        visibilityJob?.cancel()
        invalidateConnectionTest()
        super.onDetachedFromWindow()
    }

}
