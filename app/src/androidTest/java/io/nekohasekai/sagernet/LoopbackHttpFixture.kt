package io.nekohasekai.sagernet

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Synthetic HTTP server: no remote request or account credentials. */
internal class LoopbackHttpFixture : AutoCloseable {
    data class Reply(val status: Int = 200, val body: String, val headers: String = "")
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port get() = listener.localPort
    val reply = AtomicReference(Reply(body = ""))
    val requests = AtomicInteger()
    private val worker = thread(isDaemon = true, name = "rust-http-fixture") {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            socket.use {
                it.soTimeout = 5000
                val reader = it.getInputStream().bufferedReader()
                var bytes = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    bytes += line.length
                    check(bytes <= 16384)
                    if (line.isEmpty()) break
                }
                requests.incrementAndGet()
                val current = reply.get()
                val body = current.body.toByteArray(Charsets.UTF_8)
                it.getOutputStream().apply {
                    write(("HTTP/1.1 ${current.status} Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" + current.headers + "\r\n").toByteArray())
                    write(body); flush()
                }
            }
        }
    }
    override fun close() { listener.close(); worker.join(6000) }
}
