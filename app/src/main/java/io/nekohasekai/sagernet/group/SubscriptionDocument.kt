package io.nekohasekai.sagernet.group

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import io.nekohasekai.sagernet.ktx.readProfileText
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object SubscriptionDocument {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun read(resolver: ContentResolver, uri: Uri): String? = coroutineScope {
        val signal = CancellationSignal()
        val stream = AtomicReference<InputStream?>()
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                try { signal.cancel() } finally {
                    runCatching { stream.get()?.close() }
                }
            }
            launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                try {
                    ensureActive()
                    val text = resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                        descriptor.createInputStream().use { input ->
                            stream.set(input)
                            ensureActive()
                            input.readProfileText().also { ensureActive() }
                        }
                    }
                    continuation.resume(text)
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally { stream.set(null) }
            }
        }
    }
}
