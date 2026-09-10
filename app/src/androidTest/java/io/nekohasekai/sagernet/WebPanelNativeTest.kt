package io.nekohasekai.sagernet

import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceError
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.WebviewFragment
import moe.matsuri.nb4a.TempDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real WebView + localhost HTTP, opt-in on a connected physical device. No external endpoint. */
@RunWith(AndroidJUnit4::class)
class WebPanelNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var config: List<KeyValuePair>
    private lateinit var cache: List<KeyValuePair>
    private lateinit var server: ServerSocket
    private val executor = Executors.newCachedThreadPool()
    private val fail = AtomicBoolean(true)
    private val baseUrl get() = "http://127.0.0.1:${server.localPort}"

    @Before fun prepare() {
        Assume.assumeTrue("Opt in on a physical device: -e vialenWebPanel true",
            InstrumentationRegistry.getArguments().getString("vialenWebPanel") == "true")
        check(DataStore.serviceState == BaseService.State.Stopped || DataStore.serviceState == BaseService.State.Idle)
        config = PublicDatabase.kvPairDao.all().map { it.deepCopy() }
        cache = TempDatabase.profileCacheDao.all().map { it.deepCopy() }
        DataStore.configurationStore.putBoolean("isAutoConnect", false)
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                executor.submit {
                    socket.use {
                        it.soTimeout = 5000
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) { /* headers */ }
                        val failure = path.startsWith("/fail") && fail.get()
                        val title = when { failure -> "failed"; path.startsWith("/two") -> "two"; path.startsWith("/fail") -> "recovered"; else -> "one" }
                        val body = "<html><head><title>$title</title></head><body><a id='next' href='/two'>Next</a></body></html>".toByteArray()
                        val status = if (failure) "503 Unavailable" else "200 OK"
                        val headers = "HTTP/1.1 $status\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        try { it.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() } } catch (_: Exception) { }
                    }
                }
            }
        }
        DataStore.yacdURL = "$baseUrl/one"
    }

    @After fun restore() {
        if (::server.isInitialized) server.close()
        executor.shutdownNow()
        if (::config.isInitialized) {
            TempDatabase.profileCacheDao.reset(); cache.forEach { TempDatabase.profileCacheDao.put(it) }
            PublicDatabase.kvPairDao.reset(); config.forEach { PublicDatabase.kvPairDao.put(it) }
        }
    }
    private fun KeyValuePair.deepCopy() = KeyValuePair(key).also { it.valueType = valueType; it.value = value.copyOf() }
    private fun panel(activity: MainActivity) = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as WebviewFragment
    private fun browser(activity: MainActivity) = panel(activity).requireView().findViewById<WebView>(R.id.webview)
    private val tracedBrowsers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<WebView, Boolean>())
    private fun panelState(fragment: WebviewFragment, web: WebView): String = runCatching {
        fun field(name: String) = WebviewFragment::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(fragment)
        val history = web.copyBackForwardList()
        "currentUrl=${field("currentUrl")} failed=${field("failed")} historySize=${history.size} " +
            "historyIndex=${history.currentIndex} history=${(0 until history.size).map { history.getItemAtIndex(it).url }}"
    }.getOrElse { "stateProbeFailed=${it.javaClass.simpleName}" }

    /** Observe every production override without changing its return value or callback ordering. */
    private fun traceClient(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < 26) return // Public original-client getter was added in API 26.
        val web = browser(activity)
        if (!tracedBrowsers.add(web)) return
        val fragment = panel(activity)
        val original = web.webViewClient
        fun trace(event: String) {
            runCatching {
                android.util.Log.i("WebPanelTrace", "ns=${System.nanoTime()} web=${System.identityHashCode(web)} " +
                    "$event actualUrl=${web.url} title=${web.title} ${panelState(fragment, web)}")
            }
        }
        trace("install original=${original.javaClass.name}")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                trace("before onPageStarted url=$url")
                original.onPageStarted(view, url, favicon)
                trace("after onPageStarted url=$url")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                trace("before onPageFinished url=$url")
                original.onPageFinished(view, url)
                trace("after onPageFinished url=$url")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                trace("before shouldOverride request=${request?.url} main=${request?.isForMainFrame}")
                return original.shouldOverrideUrlLoading(view, request).also { trace("after shouldOverride result=$it") }
            }
            @Deprecated("Delegate the production legacy callback")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                trace("before legacyShouldOverride url=$url")
                return original.shouldOverrideUrlLoading(view, url).also { trace("after legacyShouldOverride result=$it") }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                trace("before onReceivedError url=${request?.url} main=${request?.isForMainFrame} code=${error?.errorCode}")
                original.onReceivedError(view, request, error)
                trace("after onReceivedError")
            }
            @Deprecated("Delegate the production legacy callback")
            override fun onReceivedError(view: WebView?, code: Int, description: String?, url: String?) {
                trace("before legacyError url=$url code=$code")
                original.onReceivedError(view, code, description, url)
                trace("after legacyError")
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                trace("before onReceivedHttpError url=${request?.url} main=${request?.isForMainFrame} status=${response?.statusCode}")
                original.onReceivedHttpError(view, request, response)
                trace("after onReceivedHttpError")
            }
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                trace("before history url=$url reload=$isReload")
                original.doUpdateVisitedHistory(view, url, isReload)
                trace("after history")
            }
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                trace("before commitVisible url=$url")
                original.onPageCommitVisible(view, url)
                trace("after commitVisible")
            }
        }
    }
    private fun open(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            it.displayFragmentWithId(R.id.nav_traffic); it.supportFragmentManager.executePendingTransactions()
            traceClient(it)
        }
        awaitUi(scenario) { browser(it).title == "one" && panel(it).requireView().findViewById<View>(R.id.panel_progress).visibility != View.VISIBLE }
    }
    private fun awaitUi(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val diagnostic = AtomicReference("No UI probe has executed")
        val handler = Handler(Looper.getMainLooper())
        lateinit var check: Runnable
        scenario.onActivity { activity ->
            check = Runnable {
                try {
                    val web = browser(activity)
                    traceClient(activity)
                    diagnostic.set("url=${web.url} title=${web.title} lifecycle=${activity.lifecycle.currentState} " +
                        "focus=${activity.hasWindowFocus()} finishing=${activity.isFinishing} " +
                        "browserShown=${web.isShown} visibility=${web.visibility} canGoBack=${web.canGoBack()} " +
                        panelState(panel(activity), web))
                    if (condition(activity)) done.countDown() else handler.postDelayed(check, 25)
                } catch (error: Throwable) { failure.set(error); done.countDown() }
            }
            handler.post(check)
        }
        try {
            val completed = done.await(15, TimeUnit.SECONDS)
            if (!completed) instrumentation.sendStatus(0, android.os.Bundle().apply {
                putString("webPanelTimeout", diagnostic.get())
            })
            assertTrue("WebView reached expected UI state; last probe: ${diagnostic.get()}", completed)
        }
        finally { handler.removeCallbacks(check) }
        failure.get()?.let { throw it }
    }
    private fun dialog(fragment: WebviewFragment): AlertDialog = WebviewFragment::class.java.getDeclaredField("urlDialog")
        .apply { isAccessible = true }.get(fragment) as AlertDialog
    private fun input(view: View): EditText? = when (view) {
        is EditText -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { input(view.getChildAt(it)) }
        else -> null
    }
    private fun changeUrl(scenario: ActivityScenario<MainActivity>, url: String) {
        scenario.onActivity { activity ->
            val fragment = panel(activity)
            fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.action_set_url))
            requireNotNull(input(dialog(fragment).window!!.decorView)).setText(url)
        }
        // Dialog's OnShow callback installs the validating button listener asynchronously.
        instrumentation.waitForIdleSync()
        scenario.onActivity { dialog(panel(it)).getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
    }

    private fun tapNextLink(scenario: ActivityScenario<MainActivity>) {
        val ready = CountDownLatch(1)
        val point = AtomicReference<FloatArray?>()
        val failure = AtomicReference<Throwable?>()
        scenario.onActivity { activity ->
            val web = browser(activity)
            assertTrue("Navigation tap requires a focused visible WebView", activity.hasWindowFocus() && web.isShown)
            // Read geometry only. A synthetic JS click is not a Chromium user gesture
            // and can leave the history entry ineligible for browser Back.
            web.evaluateJavascript("""(() => {
                const rect = document.getElementById('next').getBoundingClientRect();
                return {x: rect.left + rect.width / 2, y: rect.top + rect.height / 2,
                        width: window.innerWidth};
            })()""") { value ->
                try {
                    val geometry = org.json.JSONObject(value)
                    val width = geometry.getDouble("width")
                    check(width > 0 && web.width > 0)
                    val scale = web.width / width
                    val x = geometry.getDouble("x") * scale
                    val y = geometry.getDouble("y") * scale
                    check(x >= 0 && x < web.width && y >= 0 && y < web.height) { "Link center is outside WebView" }
                    val location = IntArray(2)
                    web.getLocationOnScreen(location)
                    point.set(floatArrayOf((location[0] + x).toFloat(), (location[1] + y).toFloat()))
                } catch (error: Throwable) { failure.set(error) }
                finally { ready.countDown() }
            }
        }
        assertTrue("Link geometry callback completed", ready.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        val coordinates = checkNotNull(point.get())
        val downTime = android.os.SystemClock.uptimeMillis()
        fun inject(action: Int) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(),
                action, coordinates[0], coordinates[1], 0)
            try {
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue("Injected link pointer event $action", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        inject(android.view.MotionEvent.ACTION_DOWN)
        try { CountDownLatch(1).await(50, TimeUnit.MILLISECONDS) }
        finally { inject(android.view.MotionEvent.ACTION_UP) }
    }

    @Test fun invalidUrlStaysEditableWithoutOverwritingSavedTarget() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario)
            var original = ""
            scenario.onActivity { activity ->
                original = DataStore.yacdURL
                val fragment = panel(activity)
                fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.action_set_url))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = panel(activity)
                val dialog = dialog(fragment)
                val field = requireNotNull(input(dialog.window!!.decorView))
                for (bad in listOf("", "https://", "javascript:alert(1)", "http://host:99999")) {
                    field.setText(bad)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(dialog.isShowing)
                    assertEquals(bad, field.text.toString())
                    assertNotNull(field.error)
                    assertEquals(original, DataStore.yacdURL)
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 26)
    fun httpFailureRetryAndLateCallbacksUseCurrentNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario)
            changeUrl(scenario, "$baseUrl/fail")
            awaitUi(scenario) { panel(it).requireView().findViewById<View>(R.id.panel_error).visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                val web = browser(activity)
                val client = web.webViewClient
                client.onPageStarted(web, "$baseUrl/one", null)
                client.onPageFinished(web, "$baseUrl/one")
                client.onPageStarted(web, "$baseUrl/fail", null)
                assertEquals("Late same-URL start must preserve the HTTP error", View.VISIBLE,
                    panel(activity).requireView().findViewById<View>(R.id.panel_error).visibility)
                client.onPageFinished(web, "$baseUrl/fail")
                assertEquals(View.VISIBLE, panel(activity).requireView().findViewById<View>(R.id.panel_error).visibility)
                fail.set(false)
                panel(activity).requireView().findViewById<View>(R.id.panel_retry).performClick()
            }
            awaitUi(scenario) { browser(it).title == "recovered" && panel(it).requireView().findViewById<View>(R.id.panel_error).visibility != View.VISIBLE }
            scenario.onActivity { activity ->
                val web = browser(activity)
                val oldRequest = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse("$baseUrl/one")
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                web.webViewClient.onReceivedHttpError(web, oldRequest,
                    WebResourceResponse("text/html", "UTF-8", 503, "Unavailable", emptyMap(), ByteArrayInputStream(byteArrayOf())))
                assertEquals(View.GONE, panel(activity).requireView().findViewById<View>(R.id.panel_error).visibility)
            }
        }
    }

    @Test fun recreationRestoresHistoryDrawerWinsBackAndCloseReleasesView() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario)
            tapNextLink(scenario)
            awaitUi(scenario) { browser(it).title == "two" && browser(it).canGoBack() }
            scenario.recreate()
            awaitUi(scenario) { browser(it).title == "two" && browser(it).canGoBack() }
            scenario.onActivity {
                it.binding.drawerLayout.openDrawer(GravityCompat.START, false)
                it.onBackPressedDispatcher.onBackPressed()
                assertEquals("two", browser(it).title)
            }
            awaitUi(scenario) { !it.binding.drawerLayout.isDrawerOpen(GravityCompat.START) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitUi(scenario) { browser(it).title == "one" }
            scenario.onActivity { activity ->
                val fragment = panel(activity)
                val oldWeb = browser(activity)
                fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.close))
                activity.supportFragmentManager.executePendingTransactions()
                assertTrue(activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment)
                assertNull(oldWeb.parent)
                assertNull(WebviewFragment::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(fragment))
            }
        }
    }
}
