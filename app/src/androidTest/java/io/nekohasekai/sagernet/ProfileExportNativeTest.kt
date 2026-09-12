package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.widget.PopupMenu
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import moe.matsuri.nb4a.TempDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real menu/registry/recreation/Room/file IO; the system document picker is intercepted. */
@RunWith(AndroidJUnit4::class)
class ProfileExportNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun copy(rows: List<KeyValuePair>) = rows.map { row -> KeyValuePair(row.key).also {
        it.valueType = row.valueType; it.value = row.value.copyOf()
    } }

    private fun assertRestored(expected: List<KeyValuePair>, actual: List<KeyValuePair>) {
        val rows = actual.associateBy { it.key }
        assertEquals(expected.map { it.key }.toSet(), rows.keys)
        expected.forEach { before ->
            val after = checkNotNull(rows[before.key])
            assertEquals(before.valueType, after.valueType)
            assertArrayEquals(before.value, after.value)
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue(message, predicate())
    }

    @Test fun exportKeepsOriginalTargetAcrossRecreationCancelAndDeletion() {
        assumeTrue("Opt in on a physical device with -e vialenProfileExport true",
            InstrumentationRegistry.getArguments().getString("vialenProfileExport") == "true")
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        check(DataStore.serviceState in listOf(BaseService.State.Idle, BaseService.State.Stopped))
        check(SagerDatabase.groupDao.allGroups().none { it.name?.startsWith("QA export ") == true }) {
            "Previous export fixture remains; externally force-stop and restore isolated Debug before retrying"
        }
        val config = copy(PublicDatabase.kvPairDao.all())
        val cache = copy(TempDatabase.profileCacheDao.all())
        val group = ProxyGroup(name = "QA export ${System.nanoTime()}")
        val folder = File(context.cacheDir, "qa-profile-export-${System.nanoTime()}").apply { check(mkdir()) }
        var scenario: ActivityScenario<MainActivity>? = null
        var completed = false
        var destroyed = false
        var failure: Throwable? = null
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, null, true)
        try {
            group.id = SagerDatabase.groupDao.createGroup(group)
            val profiles = (1..2).map { number -> ProxyEntity(groupId = group.id, userOrder = number.toLong()).putBean(SOCKSBean().apply {
                    initializeDefaultValues(); name = "Export fixture $number"
                    serverAddress = "127.0.0.1"; serverPort = 10080 + number
                }).also { it.id = SagerDatabase.proxyDao.addProxy(it) } }
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            DataStore.selectedGroup = group.id
            val active = ActivityScenario.launch(MainActivity::class.java)
            scenario = active
            fun page(activity: MainActivity) = activity.supportFragmentManager
                .findFragmentById(R.id.fragment_holder) as ConfigurationFragment
            fun ready() = await("Fixture list loaded") {
                var loaded = false
                active.onActivity { loaded = page(it).getCurrentGroupFragment()?.adapter?.itemCount == 2 }
                loaded
            }
            fun request(profile: ProxyEntity): Int {
                val hits = monitor.hits
                var requestCode = -1
                active.onActivity { activity ->
                    val child = checkNotNull(page(activity).getCurrentGroupFragment())
                    val adapter = checkNotNull(child.adapter)
                    val holder = adapter.onCreateViewHolder(child.configurationListView, 0)
                    adapter.onBindViewHolder(holder, adapter.configurationIdList.indexOf(profile.id))
                    val menu = PopupMenu(activity, child.configurationListView).menu
                    holder.onMenuItemClick(menu.add(0, R.id.action_config_export_file, 0, "Export"))
                    adapter.onViewRecycled(holder)
                    // Read the actual AndroidX registration; fail on incompatible registry schema.
                    val registry = Bundle().also(activity.activityResultRegistry::onSaveInstanceState)
                    val launched = requireNotNull(registry.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")).single()
                    val keys = requireNotNull(registry.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"))
                    requestCode = requireNotNull(registry.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"))[keys.indexOf(launched)]
                }
                assertEquals("CreateDocument was intercepted", hits + 1, monitor.hits)
                assertTrue(requestCode >= 0)
                return requestCode
            }
            fun deliver(code: Int, output: File?) {
                active.onActivity {
                    assertTrue(it.activityResultRegistry.dispatchResult(code,
                        if (output == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                        output?.let { file -> Intent().setData(Uri.fromFile(file)) }))
                    val saved = Bundle().also(page(it)::onSaveInstanceState)
                    assertFalse("Export request consumed on result or cancellation",
                        saved.containsKey("pendingExportProfileId"))
                }
                instrumentation.waitForIdleSync()
            }
            ready()
            val expected = profiles[0].exportConfig().first
            DataStore.serverConfig = "unrelated editor content must never be exported"
            val code = request(profiles[0])
            assertEquals("Opening export must not overwrite the editor cache",
                "unrelated editor content must never be exported", DataStore.serverConfig)
            active.recreate(); ready()
            val first = File(folder, "first.json")
            deliver(code, first)
            await("Original profile exported after recreation") { first.isFile && first.readText() == expected }
            assertEquals("unrelated editor content must never be exported", DataStore.serverConfig)

            val cancelled = request(profiles[0])
            deliver(cancelled, null)
            val second = File(folder, "second.json")
            val secondExpected = profiles[1].exportConfig().first
            deliver(request(profiles[1]), second)
            await("Next export uses the second target") { second.isFile && second.readText() == secondExpected }

            deliver(request(profiles[1]), folder) // A directory cannot be an output document.
            await("Output IO failure is reported") {
                var visible = false
                active.onActivity { activity ->
                    val text = activity.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                    visible = text?.text?.toString() == context.getString(R.string.action_export_err)
                }
                visible
            }
            assertTrue(folder.isDirectory)

            val deleted = request(profiles[0])
            SagerDatabase.proxyDao.deleteById(profiles[0].id)
            val missing = File(folder, "missing.json")
            deliver(deleted, missing)
            await("Missing target reports failure") {
                var visible = false
                active.onActivity { activity ->
                    val text = activity.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                    visible = text?.text?.toString() == context.getString(R.string.profile_export_target_missing)
                }
                visible
            }
            assertFalse("Missing target does not create an empty document", missing.exists())
            completed = true
        } catch (error: Throwable) {
            failure = error
        } finally {
            instrumentation.removeMonitor(monitor)
            try {
                scenario?.let { active ->
                    var owner: MainActivity? = null
                    active.onActivity { owner = it; it.finishAndRemoveTask() }
                    await("Export test Activity destroyed") { owner?.isDestroyed == true }
                    active.close()
                    destroyed = true
                }
                check(completed && destroyed) {
                    "Export completion/cleanup unconfirmed; fixture retained. Externally force-stop and restore isolated Debug"
                }
                SagerDatabase.proxyDao.deleteByGroup(group.id)
                SagerDatabase.groupDao.deleteGroup(group)
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset(); config.forEach(PublicDatabase.kvPairDao::put)
                }
                TempDatabase.profileCacheDao.reset(); cache.forEach(TempDatabase.profileCacheDao::put)
                assertRestored(config, PublicDatabase.kvPairDao.all())
                assertRestored(cache, TempDatabase.profileCacheDao.all())
                assertNull(SagerDatabase.groupDao.getById(group.id))
                assertTrue(SagerDatabase.proxyDao.getByGroup(group.id).isEmpty())
                check(folder.deleteRecursively())
            } catch (error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    @Test fun exportStreamHandlesFlushAndCloseFailures() {
        assumeTrue("Opt in on a physical device with -e vialenProfileExport true",
            InstrumentationRegistry.getArguments().getString("vialenProfileExport") == "true")
        val context = instrumentation.targetContext
        val errorMsg = context.getString(R.string.action_export_err)

        // 1. Opening failure (e.g. EISDIR / FileNotFoundException)
        val openErr = assertThrows(java.io.IOException::class.java) {
            ConfigurationFragment.writeExportConfig(
                { throw java.io.FileNotFoundException("EISDIR: Is a directory") },
                "test config",
                errorMsg
            )
        }
        assertEquals(errorMsg, openErr.message)
        assertEquals("EISDIR: Is a directory", openErr.cause?.message)

        // 2. Flush failure
        val flushFailingStream = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun flush() { throw java.io.IOException("simulated flush error") }
        }
        val flushErr = assertThrows(java.io.IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ flushFailingStream }, "test config", errorMsg)
        }
        assertEquals(errorMsg, flushErr.message)
        assertEquals("simulated flush error", flushErr.cause?.message)

        // 3. Close failure
        val closeFailingStream = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun close() { throw java.io.IOException("simulated close error") }
        }
        val closeErr = assertThrows(java.io.IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ closeFailingStream }, "test config", errorMsg)
        }
        assertEquals(errorMsg, closeErr.message)
        assertEquals("simulated close error", closeErr.cause?.message)

        // 4. Write failure
        val writeFailingStream = object : java.io.OutputStream() {
            override fun write(b: Int) { throw java.io.IOException("simulated write error") }
        }
        val writeErr = assertThrows(java.io.IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ writeFailingStream }, "test config", errorMsg)
        }
        assertEquals(errorMsg, writeErr.message)
        assertEquals("simulated write error", writeErr.cause?.message)

        // 5. Null stream
        val nullErr = assertThrows(java.io.IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ null }, "test config", errorMsg)
        }
        assertEquals(errorMsg, nullErr.message)

        // 6. Successful write
        val successStream = java.io.ByteArrayOutputStream()
        ConfigurationFragment.writeExportConfig({ successStream }, "{\"test\":\"ok\"}", errorMsg)
        assertEquals("{\"test\":\"ok\"}", successStream.toString("UTF-8"))
    }
}
