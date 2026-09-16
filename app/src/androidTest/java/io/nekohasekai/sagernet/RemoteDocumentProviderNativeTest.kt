package io.nekohasekai.sagernet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.group.SubscriptionDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs in the test APK's separate provider process, outside the target application's UID. */
class RemoteDocumentFixtureProvider : ContentProvider() {
    private val opens = AtomicInteger()
    private val writer = AtomicReference<ParcelFileDescriptor?>()

    override fun onCreate() = true
    override fun getType(uri: Uri): String = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openHeldFile(uri, mode)
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        openHeldFile(uri, mode)

    private fun openHeldFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/held" || mode != "r") throw FileNotFoundException()
        val pipe = ParcelFileDescriptor.createPipe()
        writer.getAndSet(pipe[1])?.close()
        opens.incrementAndGet()
        return pipe[0]
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        when (method) {
            "state" -> putInt("opens", opens.get())
            "release" -> writer.getAndSet(null)?.close()
            else -> error("Unsupported fixture call")
        }
    }
}

@RunWith(AndroidJUnit4::class)
class RemoteDocumentProviderNativeTest {
    @Test fun remoteHeldReadCancelsAndRevokedGrantRejectsNewOpen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = instrumentation.context
        val target = instrumentation.targetContext
        check(target.packageName.endsWith(".debug"))
        val uri = Uri.parse("content://com.vialen.app.debug.test.remote-document/held")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        fun opens() = checkNotNull(owner.contentResolver.call(uri, "state", null, null)).getInt("opens")
        owner.contentResolver.call(uri, "release", null, null)
        val initial = opens()
        owner.grantUriPermission(target.packageName, uri, flags)
        val read = async(Dispatchers.IO) { SubscriptionDocument.read(target.contentResolver, uri) }
        try {
            withTimeout(5_000) { while (opens() == initial) delay(25) }
            assertFalse("Remote document must hold the read open", read.isCompleted)
            withTimeout(5_000) { read.cancelAndJoin() }
            owner.revokeUriPermission(target.packageName, uri, flags)
            val denied = withTimeout(5_000) { runCatching { SubscriptionDocument.read(target.contentResolver, uri) } }
            assertTrue("Revoked document grant must reject a fresh open", denied.isFailure)
            assertEquals("Permission rejection must occur before provider open", initial + 1, opens())
        } finally {
            owner.contentResolver.call(uri, "release", null, null)
            owner.revokeUriPermission(target.packageName, uri, flags)
            withTimeout(5_000) { read.cancelAndJoin() }
        }
    }
}
