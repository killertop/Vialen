package moe.matsuri.nb4a.net

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException

object LocalResolverImpl : LocalDNSTransport {

    // new local

    private const val RCODE_NXDOMAIN = 3

    override fun raw(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    override fun networkHandle(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return SagerNet.underlyingNetwork?.networkHandle ?: 0
        }
        return 0
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)
        val underlyingNetwork = SagerNet.underlyingNetwork

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                ctx.rawSuccess(answer)
            }

            override fun onError(error: DnsResolver.DnsException) {
                val cause = error.cause
                if (cause is ErrnoException) {
                    ctx.errnoCode(cause.errno)
                } else {
                    Logs.w(error)
                    ctx.errnoCode(114514)
                }
            }
        }

        DnsResolver.getInstance().rawQuery(
            underlyingNetwork,
            message,
            DnsResolver.FLAG_NO_RETRY,
            Dispatchers.IO.asExecutor(),
            signal,
            callback
        )
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            ctx.onCancel(signal::cancel)
            val underlyingNetwork = SagerNet.underlyingNetwork

            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    try {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        ctx.errnoCode(114514)
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    try {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            ctx.errnoCode(cause.errno)
                        } else {
                            Logs.w(error)
                            ctx.errnoCode(114514)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        ctx.errnoCode(114514)
                    }
                }
            }

            val type = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (type != null) {
                DnsResolver.getInstance().query(
                    underlyingNetwork,
                    domain,
                    type,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            } else {
                DnsResolver.getInstance().query(
                    underlyingNetwork,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        } else {
            runOnIoDispatcher {
                // 老版本系统，继续用阻塞的 InetAddress
                try {
                    val u = SagerNet.underlyingNetwork
                    // Do not fall back to the default network after a selected
                    // network lookup fails; that can leak a DNS query outside
                    // the network chosen by the service.
                    val answer = if (u != null) u.getAllByName(domain)
                    else InetAddress.getAllByName(domain)
                    ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                } catch (e: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                } catch (e: Exception) {
                    Logs.w(e)
                    ctx.errnoCode(114514)
                }
            }
        }
    }

}
