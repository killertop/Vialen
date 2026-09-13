package io.nekohasekai.sagernet.database

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Manual bootstrap downloads. The core keeps ownership of live/automatic updates. */
class RuleSetDownloads(private val filesDir: File) {
    data class Status(val checked: Long = 0, val error: String = "")
    companion object {
        private val updateLock = Mutex()
        fun key(ref: RouteRuleSet): String = MessageDigest.getInstance("SHA-256")
            .digest("${ref.format}\n${ref.source}".toByteArray()).joinToString("") { "%02x".format(it) }
        fun file(root: File, ref: RouteRuleSet) = File(root, "remote-rule-sets/${key(ref)}.${if (ref.format == "binary") "srs" else "json"}")
        fun references(rows: List<RuleEntity>): List<RouteRuleSet> = rows.flatMap { RouteRuleSet.decode(it.ruleSets) }
            .filter { it.source.startsWith("https://") }.distinctBy { key(it) }
    }
    private fun statusFile(ref: RouteRuleSet) = File(filesDir, "remote-rule-sets/${key(ref)}.status")
    fun status(ref: RouteRuleSet): Status = runCatching {
        val o = JsonParser.parseString(statusFile(ref).readText()).asJsonObject
        Status(o["checked"].asLong, o["error"].asString)
    }.getOrDefault(Status())

    suspend fun update(ref: RouteRuleSet, validate: (File, String) -> Unit,
        download: suspend (RouteRuleSet, File) -> Unit = ::download): Status = withContext(Dispatchers.IO) {
        updateLock.withLock {
            ref.validate(); require(ref.source.startsWith("https://"))
            val destination = file(filesDir, ref)
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            val staged = File.createTempFile("download-", ".tmp", destination.parentFile)
            try {
                download(ref, staged)
                currentCoroutineContext().ensureActive()
                require(staged.length() in 1..32L * 1024 * 1024) { "Rule set must be between 1 byte and 32 MiB" }
                validate(staged, ref.format)
                currentCoroutineContext().ensureActive()
                check(staged.renameTo(destination)) { "Cannot replace rule-set file" }
                Status(System.currentTimeMillis()).also { save(ref, it) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status(ref).copy(error = e.message ?: e.javaClass.simpleName).also { save(ref, it) }
            } finally { staged.delete() }
        }
    }
    private fun save(ref: RouteRuleSet, status: Status) {
        val target = statusFile(ref)
        val temp = File.createTempFile("status-", ".tmp", target.parentFile)
        try {
            temp.writeText(JsonObject().apply { addProperty("checked", status.checked); addProperty("error", status.error) }.toString())
            check(temp.renameTo(target)) { "Cannot save update status" }
        } finally { temp.delete() }
    }
    private suspend fun download(ref: RouteRuleSet, target: File) {
        var url = URL(ref.source)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(url.protocol == "https") { "HTTPS is required" }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "Vialen/Android")
                val code = connection.responseCode
                if (code in listOf(429, 500, 502, 503, 504) && it < 2) {
                    connection.disconnect()
                    kotlinx.coroutines.delay((it + 1) * 500L)
                } else if (code in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing redirect location"))
                } else {
                    require(code == 200) { "HTTP $code" }
                    val deadline = System.nanoTime() + 60_000_000_000L
                    connection.inputStream.use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(8192); var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(System.nanoTime() < deadline) { "Download timed out" }
                            val n = input.read(buffer); if (n < 0) break
                            size += n; require(size <= 32L * 1024 * 1024) { "Rule set exceeds 32 MiB" }
                            output.write(buffer, 0, n)
                        }
                    } }
                    return
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
}
