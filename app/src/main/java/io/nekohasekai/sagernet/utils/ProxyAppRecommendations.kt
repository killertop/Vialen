package io.nekohasekai.sagernet.utils

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ProxyAppRecommendations {
    const val SOURCE = "https://raw.githubusercontent.com/2dust/androidpackagenamelist/master/proxy.txt"
    private const val MAX_BYTES = 1024 * 1024
    private const val MIN_RULES = 100
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")

    data class App(val packageName: String, val uid: Int)
    data class Plan(val packages: Set<String>, val matched: Int, val changed: Int)
    data class Rules(val packages: Set<String>, val refreshed: Boolean, val refreshFailed: Boolean)

    fun parse(text: String): Set<String> = text.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .onEach { require(PACKAGE_NAME.matches(it)) { "Invalid package name" } }
        .toSet().also { require(it.size >= MIN_RULES) { "Rule list is unexpectedly small" } }

    fun plan(installed: Collection<App>, current: Set<String>, recommended: Set<String>, bypass: Boolean): Plan {
        val recommendedUids = installed.asSequence().filter { it.packageName in recommended }.map { it.uid }.toSet()
        val matched = installed.asSequence().filter { it.uid in recommendedUids }.map { it.packageName }.toSet()
        val packages = if (bypass) current - matched else current + matched
        return Plan(packages, matched.size, (current union packages).count { (it in current) != (it in packages) })
    }

    suspend fun load(context: Context): Rules {
        val builtIn = context.assets.open("proxy_packagename.txt").bufferedReader().use { parse(it.readText()) }
        val cache = File(context.filesDir, "proxy-app-rules.txt")
        val cached = runCatching { cache.takeIf(File::isFile)?.readText()?.let(::parse) }.getOrNull()
        if (cached != null && System.currentTimeMillis() - cache.lastModified() <= MAX_AGE_MS)
            return Rules(cached, refreshed = false, refreshFailed = false)
        return try {
            val downloaded = download()
            val parsed = parse(downloaded)
            val staged = File.createTempFile("proxy-app-rules-", ".tmp", context.filesDir)
            try {
                staged.writeText(downloaded)
                check(staged.renameTo(cache)) { "Cannot replace app recommendation rules" }
            } finally {
                if (staged.exists()) staged.delete()
            }
            Rules(parsed, refreshed = true, refreshFailed = false)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Rules(cached ?: builtIn, refreshed = false, refreshFailed = true)
        }
    }

    private suspend fun download(): String {
        var url = URL(SOURCE)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(url.protocol == "https") { "HTTPS is required" }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect location"))
                } else {
                    require(code == 200) { "HTTP $code" }
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_BYTES) { "Rule list is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                    return output.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }
}
