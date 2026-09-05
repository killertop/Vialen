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
    private val worker = thread(isDaemon = true, name = "rust-socks-fixture") {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            active.add(socket)
            clients.submit {
                try { socket.use { serve(it) } } finally { active.remove(socket) }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 8000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        check(input.readUnsignedByte() == 5)
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        check(methods.contains(0))
        output.write(byteArrayOf(5,0)); output.flush()
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
        input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
            else -> error("Unexpected address family")
        }
        check(host == "198.18.0.254" && input.readUnsignedShort() == 80)
        output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0)); output.flush()
        val reader = input.bufferedReader()
        check(reader.readLine() == "GET /$nonce HTTP/1.1")
        var size = 0
        while (true) {
            val line = reader.readLine() ?: break
            size += line.length; check(size < 16384)
            if (line.isEmpty()) break
        }
        requests.incrementAndGet()
        val body = "RUST_VPN_E2E_$nonce".toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(body); output.flush()
    }
    override fun close() {
        listener.close(); active.forEach { it.close() }; clients.shutdownNow(); worker.join(2000)
    }
}
