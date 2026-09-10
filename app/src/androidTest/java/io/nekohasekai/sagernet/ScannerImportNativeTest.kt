package io.nekohasekai.sagernet

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.king.zxing.util.CodeUtils
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ScannerActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synthetic QR pixels and real decoder/import/Room only; no lens or system picker claim. */
@RunWith(AndroidJUnit4::class)
class ScannerImportNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun decodedQrImportsAndInvalidQrLeavesRoomUnchanged() {
        val context = instrumentation.targetContext
        assumeTrue("Opt in on a physical device with -e vialenScannerImport true",
            InstrumentationRegistry.getArguments().getString("vialenScannerImport") == "true")
        // Check before opening any application database or Activity.
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug")) { "Debug target required" }
        val journal = AtomicFile(File(context.filesDir, "scanner-import-recovery.json"))
        check(!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists() &&
            !File(journal.baseFile.path + ".new").exists()) {
            "Scanner recovery journal exists; externally force-stop and restore the isolated Debug target before retrying"
        }
        assumeTrue("Camera permission must already be granted",
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        check(DataStore.serviceState == BaseService.State.Stopped || DataStore.serviceState == BaseService.State.Idle)
        val dao = PublicDatabase.kvPairDao
        val before = linkedMapOf<String, KeyValuePair?>()
        PublicDatabase.instance.runInTransaction {
            ProfileSelectionStateRule.keys.forEach { key ->
                before[key] = dao[key]?.let { row -> KeyValuePair(row.key).also {
                    it.valueType = row.valueType; it.value = row.value.copyOf()
                } }
            }
        }
        val group = ProxyGroup(name = "QA synthetic QR ${System.nanoTime()}")
        var pending = false
        val state = JSONObject().put("fixtureName", group.name).put("fixtureId", 0)
            .put("recovery", "Externally force-stop before restoring selection and removing fixture")
        val values = JSONObject()
        before.forEach { (key, row) -> values.put(key, if (row == null) JSONObject().put("absent", true)
            else JSONObject().put("absent", false).put("type", row.valueType)
                .put("base64", Base64.encodeToString(row.value, Base64.NO_WRAP))) }
        state.put("selection", values)
        fun save(stage: String) {
            state.put("stage", stage).put("pending", pending).put("fixtureId", group.id)
            val bytes = state.toString().toByteArray(Charsets.UTF_8)
            val stream = journal.startWrite()
            try {
                stream.write(bytes)
                journal.finishWrite(stream)
            } catch (error: Throwable) { journal.failWrite(stream); throw error }
            check(journal.openRead().use { it.readBytes() }.contentEquals(bytes)) {
                "Scanner recovery journal readback failed; further changes refused"
            }
        }
        // Durable intent precedes fixture creation; the unique name recovers a crash before ID persistence.
        save("before-fixture")
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        attempt {
            group.id = SagerDatabase.groupDao.createGroup(group)
            save("fixture-created")
            val app = context.applicationContext as Application
            val scanner = AtomicReference<ScannerActivity?>()
            val resumed = CountDownLatch(1)
            val destroyed = CountDownLatch(1)
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    if (activity is ScannerActivity) {
                        // Called from super.onCreate, before the camera starts.
                        activity.finished.set(true)
                        scanner.set(activity)
                    }
                }
                override fun onActivityResumed(activity: Activity) {
                    if (activity === scanner.get()) {
                        (activity as ScannerActivity).cameraScan.setAnalyzeImage(false)
                        resumed.countDown()
                    }
                }
                override fun onActivityDestroyed(activity: Activity) { if (activity === scanner.get()) destroyed.countDown() }
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            try {
                DataStore.selectedGroup = group.id
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(context, ScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                assertTrue("Scanner resumed", resumed.await(10, TimeUnit.SECONDS))
                val activity = checkNotNull(scanner.get())
                val valid = decode("socks://127.0.0.1:10081#SyntheticQR")
                pending = true
                save("valid-submitted")
                instrumentation.runOnMainSync { assertTrue(activity.onScanResultCallback(valid, true)) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (activity.importedN.get() != 1 && System.nanoTime() < deadline) Thread.sleep(25)
                assertEquals("Production import completed", 1, activity.importedN.get())
                // This fixed payload has exactly one profile; its counter follows the final Room/selection write.
                pending = false
                save("valid-completed")
                val rows = SagerDatabase.proxyDao.getByGroup(group.id)
                assertEquals(1, rows.size)
                val bean = checkNotNull(rows.single().socksBean)
                assertEquals("127.0.0.1", bean.serverAddress)
                assertEquals(10081, bean.serverPort)
                assertEquals("SyntheticQR", bean.name)
                val allIdsBeforeInvalid = SagerDatabase.proxyDao.getAll().map { it.id }.sorted()
                val invalid = decode("this is not a proxy configuration")
                val errorText = context.getString(R.string.action_import_err)
                // The production error toast is emitted after parseRaw has rejected the payload.
                pending = true
                save("invalid-submitted")
                val event = instrumentation.uiAutomation.executeAndWaitForEvent({
                    instrumentation.runOnMainSync { assertTrue(activity.onScanResultCallback(invalid, true)) }
                }, { event ->
                    event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.packageName?.toString() == context.packageName &&
                        event.text.any { it.toString().contains(errorText) }
                }, 10000)
                event.recycle()
                pending = false
                save("invalid-completed")
                assertEquals(1, activity.importedN.get())
                assertEquals(rows.map { it.id }, SagerDatabase.proxyDao.getByGroup(group.id).map { it.id })
                assertEquals(allIdsBeforeInvalid, SagerDatabase.proxyDao.getAll().map { it.id }.sorted())
                assertEquals(group.id, DataStore.selectedGroup)
            } catch (error: Throwable) {
                failure = error
            } finally {
                var activityDestroyed = false
                attempt {
                    val activity = checkNotNull(scanner.get()) { "Scanner destruction cannot be confirmed" }
                    instrumentation.runOnMainSync { activity.finish() }
                    check(destroyed.await(10, TimeUnit.SECONDS)) { "Scanner did not reach destroyed" }
                    activityDestroyed = true
                }
                app.unregisterActivityLifecycleCallbacks(callbacks)
                attempt {
                    check(!pending && activityDestroyed) {
                        "Scanner work/cleanup unconfirmed; journal and fixture retained. Externally force-stop and restore the isolated Debug target"
                    }
                    save("restoring")
                    SagerDatabase.instance.runInTransaction {
                        SagerDatabase.proxyDao.deleteByGroup(group.id)
                        SagerDatabase.groupDao.deleteGroup(group)
                    }
                    PublicDatabase.instance.runInTransaction {
                        before.forEach { (key, row) -> if (row == null) dao.delete(key) else dao.put(row) }
                    }
                    before.forEach { (key, expected) ->
                        val actual = dao[key]
                        check(if (expected == null) actual == null else actual != null &&
                            actual.valueType == expected.valueType && actual.value.contentEquals(expected.value)) {
                            "Scanner selection restoration mismatch"
                        }
                    }
                    check(SagerDatabase.proxyDao.getByGroup(group.id).isEmpty()) { "Scanner fixture rows remain" }
                    check(SagerDatabase.groupDao.getById(group.id) == null) { "Scanner fixture group remains" }
                    journal.delete()
                    check(!journal.baseFile.exists()) { "Scanner recovery journal removal failed" }
                }
            }
        }
        if (journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists() ||
            File(journal.baseFile.path + ".new").exists()) {
            attempt { error("Scanner recovery journal retained; externally force-stop and restore the isolated Debug target before retrying") }
        }
        failure?.let { throw it }
    }

    private fun decode(text: String): com.google.zxing.Result {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        return try {
            checkNotNull(CodeUtils.parseCodeResult(bitmap)).also {
                assertEquals(BarcodeFormat.QR_CODE, it.barcodeFormat)
                assertEquals(text, it.text)
            }
        } finally { bitmap.recycle() }
    }
}
