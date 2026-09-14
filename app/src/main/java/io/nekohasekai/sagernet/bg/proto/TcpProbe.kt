package io.nekohasekai.sagernet.bg.proto

import android.net.Network
import io.nekohasekai.sagernet.database.ConnectionTestResult
import io.nekohasekai.sagernet.utils.TcpConnectionFailure
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/** One captured network owns both DNS and the socket; never fall back into the active VPN. */
internal suspend fun tcpProbe(id: Long, document: String, network: Network?, host: String, port: Int): ConnectionTestResult {
    if (network == null) return ConnectionTestResult(id, -1, 0, "当前无可用网络，请联网后重试", document)
    return try {
        val elapsed = runTcpProbe(
            resolve = { network.getAllByName(host).firstOrNull()?.hostAddress ?: throw UnknownHostException() },
            socket = { network.socketFactory.createSocket() }, port = port,
        )
        ConnectionTestResult(id, 1, elapsed, null, document)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        val message = if (error is UnknownHostException) "域名解析失败，请检查网络" else when (TcpConnectionFailure.classify(error)) {
            TcpConnectionFailure.TIMEOUT -> "连接超时，请重试"
            TcpConnectionFailure.REFUSED -> "连接被拒绝，请检查节点"
            TcpConnectionFailure.UNREACHABLE -> "网络不可达，请检查连接"
            else -> "连接失败，请检查节点或网络"
        }
        ConnectionTestResult(id, 2, 0, message, document)
    }
}

/** Injectable real-socket lifecycle, also used by offline/DNS/cancellation regressions. */
internal suspend fun runTcpProbe(resolve: () -> String, socket: () -> Socket, port: Int): Int = coroutineScope {
    val address = runInterruptible(Dispatchers.IO) { resolve() }
    val connection = socket()
    val closeOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { runCatching { connection.close() } }
    }
    try {
        runInterruptible(Dispatchers.IO) {
            connection.soTimeout = 3000
            connection.bind(InetSocketAddress(0))
            val start = System.nanoTime()
            connection.connect(InetSocketAddress(address, port), 3000)
            ((System.nanoTime() - start) / 1_000_000).toInt()
        }
    } finally {
        withContext(NonCancellable) { closeOnCancel.cancelAndJoin() }
    }
}
