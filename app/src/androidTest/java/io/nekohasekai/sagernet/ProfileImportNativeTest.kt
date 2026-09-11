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
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
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
        val ownedImportFixtures = SagerDatabase.groupDao.allGroups().filter {
            it.name?.startsWith("QA import ") == true
        }
        ownedImportFixtures.forEach {
            SagerDatabase.proxyDao.deleteByGroup(it.id)
            SagerDatabase.groupDao.deleteById(it.id)
        }
        check(SagerDatabase.groupDao.allGroups().none { it.name?.startsWith("QA import ") == true }) {
            "Previous import fixture remains; externally force-stop and restore isolated Debug before retrying"
        }
        val config = copy(PublicDatabase.kvPairDao.all())
        val cache = copy(TempDatabase.profileCacheDao.all())
        val fallbackTargetGroup = SagerDatabase.groupDao.allGroups().first { it.type == GroupType.BASIC }
        val fallbackTargetGroupId = fallbackTargetGroup.id
        val initialFallbackCount = SagerDatabase.proxyDao.getByGroup(fallbackTargetGroupId).size

        val maxOrder = SagerDatabase.groupDao.allGroups().maxOfOrNull { it.userOrder } ?: 0L
        val groups = mutableListOf<ProxyGroup>()
        repeat(2) { index -> groups += ProxyGroup(
            name = "QA import ${System.nanoTime()} ${index + 1}", userOrder = maxOrder + index + 1
        ) }
        groups += ProxyGroup(
            name = "QA import subscription ${System.nanoTime()}",
            type = GroupType.SUBSCRIPTION,
            userOrder = maxOrder + 3,
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
            fun request(expectedTargetGroupId: Long, expectedOriginGroupId: Long = expectedTargetGroupId): Int {
                val hits = monitor.hits
                var code = -1
                active.onActivity { activity ->
                    val fragment = page(activity)
                    val menu = PopupMenu(activity, fragment.groupPager).menu
                    assertTrue(fragment.onMenuItemClick(menu.add(0, R.id.action_import_file, 0, "Import")))
                    val saved = Bundle().also(fragment::onSaveInstanceState)
                    assertEquals("Target import group ID matches expected fixture",
                        expectedTargetGroupId, saved.getLong("pendingImportGroupId"))
                    assertEquals("Origin import group ID matches expected fixture",
                        expectedOriginGroupId, saved.getLong("pendingImportOriginGroupId"))
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
            val original = request(expectedTargetGroupId = groups[0].id, expectedOriginGroupId = groups[0].id)
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
            deliver(request(expectedTargetGroupId = fallbackTargetGroupId, expectedOriginGroupId = groups[2].id))
            await("Subscription-origin import uses the basic fallback target") {
                SagerDatabase.proxyDao.getByGroup(fallbackTargetGroupId).size == initialFallbackCount + 2
            }
            await("Unchanged subscription origin selects its import target") {
                DataStore.selectedGroup == fallbackTargetGroupId
            }
            select(groups[1])
            deliver(request(expectedTargetGroupId = groups[1].id, expectedOriginGroupId = groups[1].id), cancelled = true)
            val deleted = request(expectedTargetGroupId = groups[1].id, expectedOriginGroupId = groups[1].id)
            runBlocking { GroupManager.deleteGroup(groups[1].id) }
            deliver(deleted)
            messageVisible(R.string.profile_import_target_missing)
            assertTrue("Deleted target never receives profiles", SagerDatabase.proxyDao.getByGroup(groups[1].id).isEmpty())
            assertEquals(2, SagerDatabase.proxyDao.getByGroup(groups[0].id).size)
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
                groups.forEach { group ->
                    SagerDatabase.proxyDao.deleteByGroup(group.id)
                    SagerDatabase.groupDao.deleteById(group.id)
                }
                if (fallbackTargetGroupId !in groups.map { it.id }) {
                    SagerDatabase.proxyDao.getByGroup(fallbackTargetGroupId)
                        .filter { it.requireBean().serverPort in setOf(10081, 10082) }
                        .forEach { SagerDatabase.proxyDao.deleteById(it.id) }
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
                check(completed && destroyed) {
                    "Import completion/cleanup unconfirmed; fixture retained. Externally force-stop and restore isolated Debug"
                }
                check(folder.deleteRecursively())
            } catch (error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    @Test fun groupPageSelectionSwipeDeletionAndReloadPreservesStableGroupId() {
        assumeTrue("Opt in on a physical device with -e vialenProfileImport true",
            InstrumentationRegistry.getArguments().getString("vialenProfileImport") == "true")
        val context = instrumentation.targetContext
        check(BuildConfig.DEBUG && context.packageName.endsWith(".debug"))
        check(DataStore.serviceState in listOf(BaseService.State.Idle, BaseService.State.Stopped))
        val ownedSwipeFixtures = SagerDatabase.groupDao.allGroups().filter {
            it.name?.startsWith("QA swipe ") == true ||
            it.name?.startsWith("QA reload fixture ") == true ||
            it.name?.startsWith("QA hidden ungrouped ") == true
        }
        ownedSwipeFixtures.forEach {
            SagerDatabase.proxyDao.deleteByGroup(it.id)
            SagerDatabase.groupDao.deleteById(it.id)
        }
        check(SagerDatabase.groupDao.allGroups().none { it.name?.startsWith("QA swipe ") == true }) {
            "Previous group selection fixture remains; externally force-stop and restore isolated Debug before retrying"
        }
        val config = copy(PublicDatabase.kvPairDao.all())
        val cache = copy(TempDatabase.profileCacheDao.all())
        val maxOrder = SagerDatabase.groupDao.allGroups().maxOfOrNull { it.userOrder } ?: 0L
        val groups = mutableListOf<ProxyGroup>()
        repeat(4) { index -> groups += ProxyGroup(
            name = "QA swipe ${System.nanoTime()} ${index + 1}", userOrder = maxOrder + index + 1
        ) }
        groups += ProxyGroup(
            name = "QA swipe subscription ${System.nanoTime()}",
            type = GroupType.SUBSCRIPTION,
            userOrder = maxOrder + 5,
            subscription = SubscriptionBean().apply { initializeDefaultValues() }
        )
        var scenario: ActivityScenario<MainActivity>? = null
        var completed = false
        var destroyed = false
        var failure: Throwable? = null
        var reloadFixture: ProxyGroup? = null
        var hiddenFixture: ProxyGroup? = null
        try {
            groups.forEach { it.id = SagerDatabase.groupDao.createGroup(it) }
            DataStore.configurationStore.putBoolean("isAutoConnect", false)
            DataStore.selectedGroup = groups[0].id
            val active = ActivityScenario.launch(MainActivity::class.java)
            scenario = active
            fun page(activity: MainActivity) = activity.supportFragmentManager
                .findFragmentById(R.id.fragment_holder) as ConfigurationFragment

            await("ConfigurationFragment and groupPager ready") {
                var ready = false
                active.onActivity {
                    val p = page(it)
                    if (p.isAdapterInitialized && p.adapter.publishedGeneration > 0 && p.groupPager.width > 0) {
                        val g0Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[0].id }
                        ready = g0Pos >= 0 && p.groupPager.currentItem == g0Pos && DataStore.selectedGroup == groups[0].id
                    }
                }
                ready
            }
            active.onActivity {
                assertEquals(groups[0].id, DataStore.selectedGroup)
            }

            // 1. ViewPager2 fakeDrag forward (page groups[0] -> groups[1]); programmatic fakeDrag coverage, not actual touch gesture
            active.onActivity { activity ->
                val pager = page(activity).groupPager
                val width = pager.width.toFloat()
                if (pager.beginFakeDrag()) {
                    pager.fakeDragBy(-width * 0.8f)
                    pager.endFakeDrag()
                }
            }
            await("Settled on groups[1] after fakeDrag forward") {
                var onG1 = false
                active.onActivity {
                    val p = page(it)
                    val g1Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[1].id }
                    onG1 = g1Pos >= 0 && p.groupPager.currentItem == g1Pos && DataStore.selectedGroup == groups[1].id
                }
                onG1
            }

            // 2. ViewPager2 fakeDrag backward (page groups[1] -> groups[0]); programmatic fakeDrag coverage, not actual touch gesture
            active.onActivity { activity ->
                val pager = page(activity).groupPager
                val width = pager.width.toFloat()
                if (pager.beginFakeDrag()) {
                    pager.fakeDragBy(width * 0.8f)
                    pager.endFakeDrag()
                }
            }
            await("Settled back on groups[0] after fakeDrag backward") {
                var onG0 = false
                active.onActivity {
                    val p = page(it)
                    val g0Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[0].id }
                    onG0 = g0Pos >= 0 && p.groupPager.currentItem == g0Pos && DataStore.selectedGroup == groups[0].id
                }
                onG0
            }

            // 3. Programmatic selection of groups[3] by stable ID
            active.onActivity { activity ->
                val p = page(activity)
                val g3Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[3].id }
                p.groupPager.setCurrentItem(g3Pos, false)
            }
            await("Selected groups[3] by stable ID") {
                var onG3 = false
                active.onActivity {
                    val p = page(it)
                    val g3Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[3].id }
                    onG3 = g3Pos >= 0 && p.groupPager.currentItem == g3Pos && DataStore.selectedGroup == groups[3].id
                }
                onG3
            }

            // 4. Rapid back-to-back deletion of preceding groups (groups[0] and groups[1])
            // Must preserve the selected stable ID (groups[3].id), regardless of index shifts
            runBlocking {
                GroupManager.deleteGroup(groups[0].id)
                GroupManager.deleteGroup(groups[1].id)
            }
            await("Rapid removals complete and groups[3] preserved as selected by stable ID") {
                var preserved = false
                active.onActivity {
                    val p = page(it)
                    val g3Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[3].id }
                    preserved = g3Pos >= 0 &&
                            p.groupPager.currentItem == g3Pos &&
                            DataStore.selectedGroup == groups[3].id
                }
                preserved
            }

            // 5. Select last group (groups[4], subscription) by stable ID, then delete it:
            active.onActivity { activity ->
                val p = page(activity)
                val lastPos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[4].id }
                p.groupPager.setCurrentItem(lastPos, false)
            }
            await("Selected last group (groups[4]) by stable ID") {
                var onLast = false
                active.onActivity {
                    val p = page(it)
                    val lastPos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[4].id }
                    onLast = lastPos >= 0 && p.groupPager.currentItem == lastPos && DataStore.selectedGroup == groups[4].id
                }
                onLast
            }
            runBlocking { GroupManager.deleteGroup(groups[4].id) }
            await("Last group deleted and selection clamped to previous group (groups[3]) by stable ID") {
                var clamped = false
                active.onActivity {
                    val p = page(it)
                    val g3Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[3].id }
                    clamped = g3Pos >= 0 &&
                            p.groupPager.currentItem == g3Pos &&
                            DataStore.selectedGroup == groups[3].id
                }
                clamped
            }

            // 6. Select groups[2] by stable ID, then delete groups[2]:
            active.onActivity { activity ->
                val p = page(activity)
                val g2Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[2].id }
                p.groupPager.setCurrentItem(g2Pos, false)
            }
            await("Selected groups[2] by stable ID") {
                var onG2 = false
                active.onActivity {
                    val p = page(it)
                    val g2Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[2].id }
                    onG2 = g2Pos >= 0 && p.groupPager.currentItem == g2Pos && DataStore.selectedGroup == groups[2].id
                }
                onG2
            }
            runBlocking { GroupManager.deleteGroup(groups[2].id) }
            await("groups[2] deleted and DataStore.selectedGroup updated to remaining fixture group (groups[3]) by stable ID") {
                var updated = false
                active.onActivity {
                    val p = page(it)
                    val g3Pos = p.adapter.groupList.indexOfFirst { g -> g.id == groups[3].id }
                    updated = g3Pos >= 0 &&
                            p.groupPager.currentItem == g3Pos &&
                            DataStore.selectedGroup == groups[3].id
                }
                updated
            }

            // 7. Selector isolation: attach a real ConfigurationFragment(select = true) instance.
            // Verifies selector lifecycle: callbacks are isolated and reload does not mutate global DataStore.selectedGroup.
            val currentSelected = DataStore.selectedGroup
            var selectorFragment: ConfigurationFragment? = null
            active.onActivity { activity ->
                val selector = ConfigurationFragment(select = true, titleRes = R.string.select_profile)
                selectorFragment = selector
                activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, selector)
                    .commitNow()
            }
            await("Selector fragment attached and initial list published") {
                var published = false
                active.onActivity {
                    val s = selectorFragment
                    if (s != null && s.isAdded && s.view != null && s.isAdapterInitialized) {
                        published = s.adapter.publishedGeneration > 0 && s.adapter.groupList.isNotEmpty()
                    }
                }
                published
            }
            assertEquals("Selector initial publication must not touch DataStore.selectedGroup",
                currentSelected, DataStore.selectedGroup)

            var selectorTargetGen = 0L
            active.onActivity {
                val s = selectorFragment!!
                selectorTargetGen = s.adapter.reloadGeneration + 1
                s.adapter.reload(true)
            }
            await("Selector manual reload published") {
                var reloaded = false
                active.onActivity {
                    val s = selectorFragment!!
                    reloaded = s.adapter.publishedGeneration >= selectorTargetGen
                }
                reloaded
            }
            assertEquals("Selector reload must not mutate global DataStore.selectedGroup",
                currentSelected, DataStore.selectedGroup)

            // Re-attach standard ConfigurationFragment(select = false) for remaining steps
            active.onActivity { activity ->
                val normal = ConfigurationFragment(select = false)
                activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, normal)
                    .commitNow()
            }
            await("Standard ConfigurationFragment restored and published") {
                var ready = false
                active.onActivity { activity ->
                    val p = page(activity)
                    if (p.isAdded && p.view != null && p.isAdapterInitialized) {
                        ready = p.adapter.publishedGeneration > 0 && p.adapter.groupList.isNotEmpty()
                    }
                }
                ready
            }

            // 8. Adapter reload while selected group is absent from the database
            val fixture = ProxyGroup(name = "QA reload fixture ${System.nanoTime()}").apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
            reloadFixture = fixture
            // Point DataStore.selectedGroup to an absent group ID
            DataStore.selectedGroup = 88888888L
            var reloadTargetGen = 0L
            active.onActivity { activity ->
                val p = page(activity)
                reloadTargetGen = p.adapter.reloadGeneration + 1
                p.adapter.reload(true)
            }
            await("Adapter reloaded with reloadFixture") {
                var reloaded = false
                active.onActivity {
                    val p = page(it)
                    reloaded = p.adapter.publishedGeneration >= reloadTargetGen &&
                            p.adapter.groupList.any { g -> g.id == fixture.id }
                }
                reloaded
            }
            active.onActivity { activity ->
                val p = page(activity)
                val visibleIndex = p.groupPager.currentItem
                val visibleGroup = p.adapter.groupList[visibleIndex]
                assertEquals("DataStore.selectedGroup resolved to visible group when previous selection was absent",
                    visibleGroup.id, DataStore.selectedGroup)
            }

            // 9. Profile added in a previously hidden group (empty ungrouped group)
            val hiddenGroup = ProxyGroup(name = "QA hidden ungrouped ${System.nanoTime()}", ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
            hiddenFixture = hiddenGroup
            var hiddenReloadGen = 0L
            active.onActivity { activity ->
                val p = page(activity)
                hiddenReloadGen = p.adapter.reloadGeneration + 1
                p.adapter.reload(true)
            }
            await("Reload completed for empty ungrouped group") {
                var done = false
                active.onActivity {
                    val p = page(it)
                    done = p.adapter.publishedGeneration >= hiddenReloadGen
                }
                done
            }
            active.onActivity { activity ->
                assertFalse("Empty ungrouped group must be hidden when size > 1",
                    page(activity).adapter.groupList.any { g -> g.id == hiddenGroup.id })
            }

            val addedProfile = ProxyEntity(
                groupId = hiddenGroup.id,
                userOrder = 1L,
                socksBean = SOCKSBean().apply {
                    initializeDefaultValues()
                    serverAddress = "127.0.0.1"
                    serverPort = 10800
                }
            ).also { it.id = SagerDatabase.proxyDao.addProxy(it) }

            var addReloadGen = 0L
            active.onActivity { activity ->
                val p = page(activity)
                addReloadGen = p.adapter.reloadGeneration + 1
                runBlocking { p.adapter.onAdd(addedProfile) }
            }
            await("Previously hidden group appears and is selected on profile addition") {
                var selected = false
                active.onActivity { activity ->
                    val p = page(activity)
                    val targetIndex = p.adapter.groupList.indexOfFirst { g -> g.id == hiddenGroup.id }
                    selected = p.adapter.publishedGeneration >= addReloadGen &&
                            targetIndex >= 0 &&
                            DataStore.selectedGroup == hiddenGroup.id &&
                            p.groupPager.currentItem == targetIndex
                }
                selected
            }

            completed = true
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                scenario?.let { active ->
                    var owner: MainActivity? = null
                    active.onActivity { owner = it; it.finishAndRemoveTask() }
                    await("Activity destroyed") { owner?.isDestroyed == true }
                    active.close()
                    destroyed = true
                }
                hiddenFixture?.let {
                    SagerDatabase.proxyDao.deleteByGroup(it.id)
                    SagerDatabase.groupDao.deleteById(it.id)
                }
                reloadFixture?.let {
                    SagerDatabase.proxyDao.deleteByGroup(it.id)
                    SagerDatabase.groupDao.deleteById(it.id)
                }
                groups.forEach { group ->
                    SagerDatabase.proxyDao.deleteByGroup(group.id)
                    SagerDatabase.groupDao.deleteById(group.id)
                }
                PublicDatabase.instance.runInTransaction {
                    PublicDatabase.kvPairDao.reset(); config.forEach(PublicDatabase.kvPairDao::put)
                }
                TempDatabase.profileCacheDao.reset(); cache.forEach(TempDatabase.profileCacheDao::put)
                assertRestored(config, PublicDatabase.kvPairDao.all())
                assertRestored(cache, TempDatabase.profileCacheDao.all())
                check(completed && destroyed) {
                    "Selection test completion/cleanup unconfirmed; fixture retained."
                }
            } catch (error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
