package io.nekohasekai.sagernet

import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** No upstream socket: the first exact HTTP request is held until release, the second is immediate. */
internal class HeldLoopbackSocksFixture(val nonce: String) : AutoCloseable {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArraySet<Socket>()
    private val clients = Executors.newCachedThreadPool { task -> Thread(task, "held-socks-client").apply { isDaemon = true } }
    private val ready = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val events = CopyOnWriteArrayList<String>()
    val heldRequests = AtomicInteger()
    val freshRequests = AtomicInteger()
    val errors = CopyOnWriteArrayList<Throwable>()
    @Volatile private var closing = false
    @Volatile private var discardHeldResponse = false
    val port get() = listener.localPort
    fun body(fresh: Boolean) = "CONTROLLED_HANDOVER_${nonce}_${if (fresh) "fresh" else "held"}"
    private fun record(value: String) { events += "ns=${System.nanoTime()} $value" }
    fun diagnosticSnapshot() = events.toList()
    fun awaitReady() { check(ready.await(15, TimeUnit.SECONDS)) { "Complete held headers not received: $events errors=$errors" }; check(errors.isEmpty()) { "Fixture errors: $errors" } }
    fun releaseHeld(discard: Boolean = false) { discardHeldResponse = discard; record("release discard=$discard"); release.countDown() }
    private val acceptor = thread(name = "held-socks-accept", isDaemon = true) {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            sockets += socket
            clients.submit {
                try { socket.use { serve(it) } } catch (error: Throwable) {
                    record("failure type=${error.javaClass.name} message=${error.message}")
                    if (!closing) errors += error
                } finally { sockets -= socket }
            }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 30000
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        check(input.readUnsignedByte() == 5)
        check(ByteArray(input.readUnsignedByte()).also(input::readFully).contains(0))
        output.write(byteArrayOf(5, 0)); output.flush()
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
        check(input.readUnsignedByte() == 0)
        val host = when (input.readUnsignedByte()) {
            1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
            else -> error("Unexpected SOCKS address family")
        }
        val targetPort = input.readUnsignedShort()
        record("target=$host:$targetPort")
        check(host == "198.18.0.254" && targetPort == 80)
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
        // Byte-level CRLF parsing never treats EOF as a completed header block.
        var total = 0
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.read()
                if (value < 0) { record("EOF_before_complete_headers"); throw EOFException("Incomplete HTTP headers") }
                check(++total <= 16384)
                if (value == 13) { check(input.read() == 10); total++; return bytes.toByteArray().toString(Charsets.US_ASCII) }
                bytes += value.toByte()
            }
        }
        val request = line()
        val fresh = when (request) {
            "GET /$nonce/held HTTP/1.1" -> false
            "GET /$nonce/fresh HTTP/1.1" -> true
            else -> error("Unexpected request: $request")
        }
        while (line().isNotEmpty()) { /* require the final empty CRLF line */ }
        val count = (if (fresh) freshRequests else heldRequests).incrementAndGet()
        record("complete_headers request=$request count=$count")
        check(count == 1) { "Automatic HTTP retry or duplicate request: $request" }
        if (!fresh) {
            // Observe peer closure independently while the response writer remains behind its gate.
            clients.submit {
                try { record("held_peer_read=${input.read()} before_release=${release.count != 0L}") }
                catch (error: Throwable) { record("held_peer_read_error=${error.javaClass.name} before_release=${release.count != 0L}") }
            }
            ready.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "Held response release deadline" }
            if (closing || discardHeldResponse) return
        }
        val payload = body(fresh).toByteArray(Charsets.UTF_8)
        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(payload); output.flush()
        record("response_flushed fresh=$fresh")
    }
    override fun close() {
        closing = true
        releaseHeld()
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else failure!!.addSuppressed(error) } }
        attempt { listener.close() }
        sockets.forEach { socket -> attempt { socket.close() } }
        clients.shutdownNow()
        attempt { acceptor.join(2000); check(!acceptor.isAlive) }
        attempt { check(clients.awaitTermination(2, TimeUnit.SECONDS)) }
        failure?.let { throw it }
    }
}
