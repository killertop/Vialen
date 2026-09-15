package io.nekohasekai.sagernet

import android.content.*
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.content.pm.ProviderInfo
import io.nekohasekai.sagernet.group.SubscriptionDocument
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=android.app.Application::class)
class SubscriptionDocumentTest {
    private class Provider(val denied: Boolean, val entered: CompletableDeferred<Unit>, val cancelled: CompletableDeferred<Unit>): ContentProvider() {
        override fun onCreate()=true
        override fun getType(uri:Uri)="text/plain"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
        override fun insert(uri:Uri,v:ContentValues?):Uri?=null
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=0
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=0
        override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: android.os.Bundle?, signal: CancellationSignal?): AssetFileDescriptor? = openAssetFile(uri, "r", signal)
        override fun openAssetFile(uri:Uri,mode:String,signal:CancellationSignal?):AssetFileDescriptor? {
            if(denied)throw SecurityException("synthetic permission revoked")
            signal!!.setOnCancelListener { cancelled.complete(Unit) }
            entered.complete(Unit)
            runBlocking { cancelled.await() }
            signal.throwIfCanceled()
            throw FileNotFoundException()
        }
    }
    @Test fun cancellationReachesProviderAndPermissionFailureIsNotRetriedAsTransport()=runBlocking {
        val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val resolver=RuntimeEnvironment.getApplication().contentResolver
        fun register(denied: Boolean) {
            val provider = Provider(denied, entered, cancelled)
            provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply { authority = "fixture"; exported = true })
            ShadowContentResolver.registerProviderInternal("fixture", provider)
        }
        register(false)
        val task=async { SubscriptionDocument.read(resolver,Uri.parse("content://fixture/document")) }
        withTimeout(3000){entered.await()};task.cancelAndJoin();assertTrue(cancelled.isCompleted)
        register(true)
        val error=runCatching{SubscriptionDocument.read(resolver,Uri.parse("content://fixture/document"))}.exceptionOrNull()
        assertTrue(error is SecurityException)
    }
}
