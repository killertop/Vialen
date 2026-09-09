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
    private fun open(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.displayFragmentWithId(R.id.nav_traffic); it.supportFragmentManager.executePendingTransactions() }
        awaitUi(scenario) { browser(it).title == "one" && panel(it).requireView().findViewById<View>(R.id.panel_progress).visibility != View.VISIBLE }
    }
    private fun awaitUi(scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val handler = Handler(Looper.getMainLooper())
        lateinit var check: Runnable
        scenario.onActivity { activity ->
            check = Runnable {
                try {
                    if (condition(activity)) done.countDown() else handler.postDelayed(check, 25)
                } catch (error: Throwable) { failure.set(error); done.countDown() }
            }
            handler.post(check)
        }
        try { assertTrue("WebView reached expected UI state", done.await(15, TimeUnit.SECONDS)) }
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
    private fun changeUrl(activity: MainActivity, url: String) {
        val fragment = panel(activity)
        fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.action_set_url))
        val dialog = dialog(fragment)
        requireNotNull(input(dialog.window!!.decorView)).setText(url)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    @Test fun invalidUrlStaysEditableWithoutOverwritingSavedTarget() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            open(scenario)
            scenario.onActivity { activity ->
                val original = DataStore.yacdURL
                val fragment = panel(activity)
                fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.action_set_url))
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
            scenario.onActivity { changeUrl(it, "$baseUrl/fail") }
            awaitUi(scenario) { panel(it).requireView().findViewById<View>(R.id.panel_error).visibility == View.VISIBLE }
            scenario.onActivity { activity ->
                val web = browser(activity)
                val client = web.webViewClient
                client.onPageStarted(web, "$baseUrl/one", null)
                client.onPageFinished(web, "$baseUrl/one")
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
            scenario.onActivity { browser(it).evaluateJavascript("document.getElementById('next').click()", null) }
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
