package io.nekohasekai.sagernet

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** SOCKS endpoint for a synthetic destination. It never opens an upstream socket. */
internal class LoopbackSocksFixture(private val nonce: String) : AutoCloseable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val active = CopyOnWriteArraySet<Socket>()
    private val clients = Executors.newCachedThreadPool { task -> Thread(task, "rust-socks-client").apply { isDaemon = true } }
    val port get() = listener.localPort
    val requests = AtomicInteger()
    private val connectionIds = AtomicInteger()
    private val completed = AtomicInteger()
    private val failed = AtomicInteger()
    private val events = java.util.ArrayDeque<String>()
    @Volatile private var closing = false
    private class ClientTrace(val id: Int) {
        var phase = "accepted"
        var target = "unknown"
    }
    private fun record(trace: ClientTrace?, phase: String, error: Throwable? = null) {
        // Diagnostics must never replace the original protocol/I/O failure.
        runCatching {
            if (trace != null) trace.phase = phase
            val stack = error?.stackTrace?.take(4)?.joinToString("|") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            val event = "ns=${System.nanoTime()} client=${trace?.id} phase=$phase target=${trace?.target} closing=$closing error=${error?.javaClass?.name} stack=$stack"
            synchronized(events) {
                if (events.size == 128) events.removeFirst()
                events.addLast(event)
            }
        }
    }
    fun diagnosticSnapshot(): List<String> = synchronized(events) {
        listOf("port=$port accepted=${connectionIds.get()} active=${active.size} completed=${completed.get()} failed=${failed.get()} requests=${requests.get()} closing=$closing retained=${events.size}/128") + events.toList()
    }
    private val worker = thread(isDaemon = true, name = "rust-socks-fixture") {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            active.add(socket)
            val trace = ClientTrace(connectionIds.incrementAndGet())
            record(trace, "accepted")
            clients.submit {
                try {
                    socket.use { serve(it, trace) }
                    completed.incrementAndGet()
                    record(trace, "completed")
                } catch (error: Throwable) {
                    failed.incrementAndGet()
                    record(trace, "failed_at_${trace.phase}", error)
                    throw error
                } finally { active.remove(socket) }
            }
        }
    }
    private fun serve(socket: Socket, trace: ClientTrace) {
        socket.soTimeout = 8000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        record(trace, "socks_greeting")
        check(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        check(methods.contains(0))
        output.write(byteArrayOf(5,0)); output.flush()
        record(trace, "socks_connect")
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
        input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
            else -> error("Unexpected address family")
        }
        trace.target = if (host == "198.18.0.254") "expected_host" else "unexpected"
        record(trace, "target_check")
        check(host == "198.18.0.254" && input.readUnsignedShort() == 80)
        trace.target = "expected"
        record(trace, "target_matched")
        output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); output.flush()
        val reader = input.bufferedReader()
        record(trace, "request_line_check")
        check(reader.readLine() == "GET /$nonce HTTP/1.1")
        record(trace, "request_line_expected")
        record(trace, "request_headers")
        var size = 0
        while (true) {
            val line = reader.readLine() ?: break
            size += line.length; check(size < 16384)
            if (line.isEmpty()) break
        }
        requests.incrementAndGet()
        record(trace, "request_counted")
        record(trace, "response_headers_write")
        val body = "RUST_VPN_E2E_$nonce".toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        record(trace, "response_body_write")
        output.write(body); output.flush()
        record(trace, "response_flushed")
    }
    override fun close() {
        closing = true
        record(null, "close_begin")
        listener.close(); active.forEach { it.close() }; clients.shutdownNow(); worker.join(2000)
    }
}
