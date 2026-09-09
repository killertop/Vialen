package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import io.nekohasekai.sagernet.ui.state.PanelUrl

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {
    private var binding: LayoutWebviewBinding? = null
    private var webView: WebView? = null
    private var savedWebState: Bundle? = null
    private var currentUrl: String? = null
    private var failed = false

    private var urlDialog: androidx.appcompat.app.AlertDialog? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)
        val ui = LayoutWebviewBinding.bind(view)
        binding = ui
        val browser = ui.webview
        webView = browser
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        browser.settings.domStorageEnabled = true
        browser.settings.javaScriptEnabled = true
        browser.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (webView !== view || !PanelUrl.sameTarget(url, currentUrl)) return
                currentUrl = url
                failed = false
                showLoading()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (webView !== view || failed || !PanelUrl.sameTarget(url, currentUrl)) return
                binding?.panelProgress?.isVisible = false
                binding?.webview?.isVisible = true
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (webView !== view || request?.isForMainFrame != true) return false
                val target = request.url.toString()
                if (!PanelUrl.isValid(target)) { showError(); return true }
                currentUrl = target
                failed = false
                showLoading()
                return false
            }
            @Deprecated("Required for Android 5 devices")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (webView !== view || url == null) return false
                if (!PanelUrl.isValid(url)) { showError(); return true }
                currentUrl = url
                failed = false
                showLoading()
                return false
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (webView === view && request?.isForMainFrame == true && PanelUrl.sameTarget(request.url.toString(), currentUrl)) showError()
            }
            @Deprecated("Required for Android 5 devices")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (webView === view && PanelUrl.sameTarget(failingUrl, currentUrl)) showError()
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (webView === view && request?.isForMainFrame == true && PanelUrl.sameTarget(request.url.toString(), currentUrl)) showError()
            }
        }
        ui.panelRetry.setOnClickListener { load(currentUrl ?: DataStore.yacdURL) }
        val state = savedInstanceState?.getBundle("webHistory") ?: savedWebState
        currentUrl = savedInstanceState?.getString("webUrl") ?: currentUrl ?: DataStore.yacdURL
        val restored = try { state?.let { browser.restoreState(it) } } catch (_: RuntimeException) { null }
        if (restored == null) load(currentUrl!!)
        else {
            currentUrl = restored.getItemAtIndex(restored.currentIndex)?.url ?: currentUrl
            failed = false
            showLoading()
            browser.reload()
        }
    }

    private fun showLoading() {
        binding?.panelProgress?.isVisible = true
        binding?.panelError?.isVisible = false
        binding?.webview?.isVisible = true
    }

    private fun showError() {
        failed = true
        binding?.panelProgress?.isVisible = false
        binding?.panelError?.isVisible = true
        binding?.webview?.isVisible = false
    }

    private fun load(url: String) {
        webView?.stopLoading()
        currentUrl = url
        if (!PanelUrl.isValid(url)) { showError(); return }
        val uri = android.net.Uri.parse(url)
        currentUrl = if (uri.encodedPath.isNullOrEmpty()) uri.buildUpon().encodedPath("/").build().toString() else url
        failed = false
        showLoading()
        try { webView?.loadUrl(currentUrl!!) } catch (_: Exception) { showError() }
    }

    /** MainActivity calls this after giving an open drawer first refusal. */
    fun consumeBack(): Boolean {
        val browser = webView ?: return false
        if (!browser.canGoBack()) return false
        val history = browser.copyBackForwardList()
        currentUrl = history.getItemAtIndex(history.currentIndex - 1)?.url ?: return false
        failed = false
        showLoading()
        browser.goBack()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = Bundle()
        if (webView?.saveState(state) != null) outState.putBundle("webHistory", state)
        else savedWebState?.let { outState.putBundle("webHistory", it) }
        outState.putString("webUrl", currentUrl)
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroyView() {
        urlDialog?.dismiss()
        urlDialog = null
        val browser = webView
        savedWebState = Bundle().takeIf { browser?.saveState(it) != null }
        webView = null
        binding = null
        browser?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            onPause()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        super.onDestroyView()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val input = EditText(requireContext()).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                    contentDescription = getString(R.string.set_panel_url)
                }
                val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create()
                urlDialog = dialog
                dialog.setOnDismissListener { if (urlDialog === dialog) urlDialog = null }
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = input.text.toString().trim()
                        if (!PanelUrl.isValid(url)) {
                            input.error = getString(R.string.panel_invalid_url)
                        } else {
                            DataStore.yacdURL = url
                            load(url)
                            dialog.dismiss()
                        }
                    }
                }
                dialog.show()
            }
            R.id.close -> (requireActivity() as MainActivity).displayFragmentWithId(R.id.nav_configuration)
        }
        return true
    }
}
