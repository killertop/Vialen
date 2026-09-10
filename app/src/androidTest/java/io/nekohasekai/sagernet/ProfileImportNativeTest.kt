package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import moe.matsuri.nb4a.TempDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

/** Real ZIP/provider/Room import; intercepts only the external document picker. */
@RunWith(AndroidJUnit4::class)
class ProfileImportNativeTest {
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

    @Test fun zipImportKeepsTargetAcrossRecreationCancelAndDeletion() {
        assumeTrue("Opt in on a physical device with -e vialenProfileImport true",
            InstrumentationRegistry.getArguments().getString("vialenProfileImport") == "true")
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        check(DataStore.serviceState in listOf(BaseService.State.Idle, BaseService.State.Stopped))
        check(SagerDatabase.groupDao.allGroups().none { it.name?.startsWith("QA import ") == true }) {
            "Previous import fixture remains; externally force-stop and restore isolated Debug before retrying"
        }
        val config = copy(PublicDatabase.kvPairDao.all())
        val cache = copy(TempDatabase.profileCacheDao.all())
        val groups = mutableListOf<ProxyGroup>()
        repeat(2) { index -> groups += ProxyGroup(
            name = "QA import ${System.nanoTime()} ${index + 1}", userOrder = (index + 1).toLong()
        ) }
        groups += ProxyGroup(
            name = "QA import subscription ${System.nanoTime()}",
            type = GroupType.SUBSCRIPTION,
            userOrder = 3,
            subscription = SubscriptionBean().apply { initializeDefaultValues() }
        )
        val folder = File(context.cacheDir, "qa-profile-import-${System.nanoTime()}").apply { check(mkdir()) }
        val archive = File(folder, "profiles.zip")
        var scenario: ActivityScenario<MainActivity>? = null
        var completed = false
        var destroyed = false
        var failure: Throwable? = null
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
        }, null, true)
        try {
            groups.forEach { it.id = SagerDatabase.groupDao.createGroup(it) }
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (number in 1..2) {
                    zip.putNextEntry(ZipEntry("profile-$number.txt"))
                    zip.write("socks://127.0.0.1:${10080 + number}#Import%20fixture%20$number".toByteArray())
                    zip.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", archive)
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            DataStore.selectedGroup = groups[0].id
            val active = ActivityScenario.launch(MainActivity::class.java)
            scenario = active
            fun page(activity: MainActivity) = activity.supportFragmentManager
                .findFragmentById(R.id.fragment_holder) as ConfigurationFragment
            fun select(group: ProxyGroup) {
                await("Import fixture group is available") {
                    var selected = false
                    active.onActivity { activity ->
                        val fragment = page(activity)
                        val index = fragment.adapter.groupList.indexOfFirst { it.id == group.id }
                        if (index >= 0) {
                            fragment.groupPager.setCurrentItem(index, false)
                            selected = DataStore.selectedGroup == group.id && fragment.getCurrentGroupFragment()?.adapter != null
                        }
                    }
                    selected
                }
            }
            fun request(): Int {
                val hits = monitor.hits
                var code = -1
                active.onActivity { activity ->
                    val fragment = page(activity)
                    val menu = PopupMenu(activity, fragment.groupPager).menu
                    assertTrue(fragment.onMenuItemClick(menu.add(0, R.id.action_import_file, 0, "Import")))
                    val saved = Bundle().also(fragment::onSaveInstanceState)
                    assertEquals(DataStore.selectedGroup, saved.getLong("pendingImportGroupId"))
                    assertEquals(DataStore.selectedGroup, saved.getLong("pendingImportOriginGroupId"))
                    val registry = Bundle().also(activity.activityResultRegistry::onSaveInstanceState)
                    val launched = requireNotNull(registry.getStringArrayList("KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS")).single()
                    val keys = requireNotNull(registry.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"))
                    code = requireNotNull(registry.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"))[keys.indexOf(launched)]
                }
                assertEquals("GetContent intercepted", hits + 1, monitor.hits)
                assertTrue(code >= 0)
                return code
            }
            fun deliver(code: Int, cancelled: Boolean = false) {
                active.onActivity { activity ->
                    assertTrue(activity.activityResultRegistry.dispatchResult(code,
                        if (cancelled) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                        if (cancelled) null else Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
                    assertFalse("Import request consumed", Bundle().also(page(activity)::onSaveInstanceState)
                        .containsKey("pendingImportGroupId"))
                    assertFalse("Import origin consumed", Bundle().also(page(activity)::onSaveInstanceState)
                        .containsKey("pendingImportOriginGroupId"))
                }
            }
            fun messageVisible(resource: Int) = await("Import result message displayed") {
                var visible = false
                active.onActivity { activity ->
                    visible = activity.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                        ?.text?.toString() == context.getString(resource)
                }
                visible
            }
            select(groups[0])
            val original = request()
            select(groups[1])
            active.recreate()
            select(groups[1])
            deliver(original)
            await("Both ZIP entries imported to original target") { SagerDatabase.proxyDao.getByGroup(groups[0].id).size == 2 }
            await("Import completed its UI callback") {
                var visible = false
                active.onActivity { activity ->
                    visible = activity.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
                        ?.text?.toString() == context.resources.getQuantityString(R.plurals.added, 2, 2)
                }
                visible
            }
            assertTrue(SagerDatabase.proxyDao.getByGroup(groups[1].id).isEmpty())
            assertEquals("A newer user selection remains selected", groups[1].id, DataStore.selectedGroup)
            assertEquals(setOf(10081, 10082), SagerDatabase.proxyDao.getByGroup(groups[0].id)
                .map { it.requireBean().serverPort }.toSet())
            select(groups[2])
            deliver(request())
            await("Subscription-origin import uses the basic fallback target") {
                SagerDatabase.proxyDao.getByGroup(groups[0].id).size == 4
            }
            await("Unchanged subscription origin selects its import target") {
                DataStore.selectedGroup == groups[0].id
            }
            select(groups[1])
            deliver(request(), cancelled = true)
            val deleted = request()
            runBlocking { GroupManager.deleteGroup(groups[1].id) }
            deliver(deleted)
            messageVisible(R.string.profile_import_target_missing)
            assertTrue("Deleted target never receives profiles", SagerDatabase.proxyDao.getByGroup(groups[1].id).isEmpty())
            assertEquals(4, SagerDatabase.proxyDao.getByGroup(groups[0].id).size)
            completed = true
        } catch (error: Throwable) {
            failure = error
        } finally {
            instrumentation.removeMonitor(monitor)
            try {
                scenario?.let { active ->
                    var owner: MainActivity? = null
                    active.onActivity { owner = it; it.finishAndRemoveTask() }
                    await("Import test Activity destroyed") { owner?.isDestroyed == true }
                    active.close()
                    destroyed = true
                }
                check(completed && destroyed) {
                    "Import completion/cleanup unconfirmed; fixture retained. Externally force-stop and restore isolated Debug"
                }
                groups.forEach { group ->
                    SagerDatabase.proxyDao.deleteByGroup(group.id)
                    SagerDatabase.groupDao.deleteGroup(group)
                }
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset(); config.forEach(PublicDatabase.kvPairDao::put)
                }
                TempDatabase.profileCacheDao.reset(); cache.forEach(TempDatabase.profileCacheDao::put)
                assertRestored(config, PublicDatabase.kvPairDao.all())
                assertRestored(cache, TempDatabase.profileCacheDao.all())
                groups.forEach {
                    assertNull(SagerDatabase.groupDao.getById(it.id))
                    assertTrue(SagerDatabase.proxyDao.getByGroup(it.id).isEmpty())
                }
                check(folder.deleteRecursively())
            } catch (error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
