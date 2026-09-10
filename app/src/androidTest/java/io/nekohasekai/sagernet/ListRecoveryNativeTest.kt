package io.nekohasekai.sagernet

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.GroupFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.RouteFragment
import io.nekohasekai.sagernet.ui.state.OrderedWorkQueue
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.TempDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in physical-device UI tests. Restores all rules and settings even after reset coverage. */
@RunWith(AndroidJUnit4::class)
class ListRecoveryNativeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var originalRules: List<RuleEntity>
    private lateinit var config: List<KeyValuePair>
    private lateinit var cache: List<KeyValuePair>
    private val queue: OrderedWorkQueue get() = RouteFragment::class.java.getDeclaredField("writes")
        .apply { isAccessible = true }.get(null) as OrderedWorkQueue

    @Before fun preserve() {
        Assume.assumeTrue("Opt in on a physical device: -e vialenLists true",
            InstrumentationRegistry.getArguments().getString("vialenLists") == "true")
        check(DataStore.serviceState == BaseService.State.Stopped || DataStore.serviceState == BaseService.State.Idle)
        config = PublicDatabase.kvPairDao.all().map { it.deepCopy() }
        cache = TempDatabase.profileCacheDao.all().map { it.deepCopy() }
        originalRules = SagerDatabase.rulesDao.allRules()
        DataStore.configurationStore.putBoolean("isAutoConnect", false)
        SagerDatabase.rulesDao.reset()
        DataStore.rulesFirstCreate = true
        (1..24).forEach { index -> SagerDatabase.rulesDao.createRule(RuleEntity(
            name = "List fixture $index", userOrder = index.toLong(), domains = "fixture$index.example", enabled = false)) }
    }

    @After fun restore() {
        if (!::originalRules.isInitialized) return
        drain()
        SagerDatabase.instance.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS vialen_list_test_failure")
        SagerDatabase.rulesDao.reset()
        SagerDatabase.rulesDao.insert(originalRules)
        TempDatabase.profileCacheDao.reset(); cache.forEach { TempDatabase.profileCacheDao.put(it) }
        PublicDatabase.kvPairDao.reset(); config.forEach { PublicDatabase.kvPairDao.put(it) }
    }

    private fun KeyValuePair.deepCopy() = KeyValuePair(key).also { it.valueType = valueType; it.value = value.copyOf() }
    private fun drain() {
        val done = CountDownLatch(1)
        queue.submit { done.countDown() }
        assertTrue("Rule writes completed", done.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun route(scenario: ActivityScenario<MainActivity>): RouteFragment {
        lateinit var fragment: RouteFragment
        scenario.onActivity { activity ->
            activity.displayFragmentWithId(R.id.nav_route)
            activity.supportFragmentManager.executePendingTransactions()
            fragment = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as RouteFragment
        }
        drain()
        return fragment
    }
    private fun heldQueue(): Pair<CompletableDeferred<Unit>, CountDownLatch> {
        val release = CompletableDeferred<Unit>()
        val entered = CountDownLatch(1)
        queue.submit { entered.countDown(); release.await() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        return release to entered
    }

    @Test fun recycledSwitchKeepsLatestIntentAndDoesNotWriteOnBind() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fragment = route(scenario)
            val release = heldQueue().first
            val secondEntered = CountDownLatch(1)
            val releaseSecond = CompletableDeferred<Unit>()
            lateinit var holder: RouteFragment.RuleAdapter.RuleHolder
            lateinit var a: RuleEntity
            lateinit var b: RuleEntity
            try {
                scenario.onActivity {
                    a = fragment.ruleAdapter.ruleList[0]
                    b = fragment.ruleAdapter.ruleList[1]
                    holder = fragment.ruleAdapter.onCreateViewHolder(fragment.ruleListView, 1) as RouteFragment.RuleAdapter.RuleHolder
                    holder.bind(a)
                    holder.enableSwitch.performClick() // true
                    queue.submit { secondEntered.countDown(); releaseSecond.await() }
                    holder.enableSwitch.performClick() // latest false
                    holder.bind(b)
                    holder.bind(a) // A -> B -> A must not fire bind writes
                    assertFalse(holder.enableSwitch.isChecked)
                }
                release.complete(Unit)
                assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertFalse("Earlier write must not roll back newer intent", fragment.ruleAdapter.ruleList.first { it.id == a.id }.enabled)
                    assertFalse(holder.enableSwitch.isChecked)
                }
            } finally { release.complete(Unit); releaseSecond.complete(Unit) }
            drain()
            assertFalse(SagerDatabase.rulesDao.getById(a.id)!!.enabled)
            assertFalse(SagerDatabase.rulesDao.getById(b.id)!!.enabled)
            assertEquals("fixture1.example", SagerDatabase.rulesDao.getById(a.id)!!.domains)
        }
    }

    @Test fun failedLatestToggleRecoversFromRoomAndKeepsOtherFields() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fragment = route(scenario)
            val rule = fragment.ruleAdapter.ruleList.first()
            SagerDatabase.instance.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER vialen_list_test_failure BEFORE UPDATE OF enabled ON rules " +
                    "WHEN NEW.id = ${rule.id} BEGIN SELECT RAISE(FAIL, 'Controlled list write failure'); END")
            scenario.onActivity {
                val holder = fragment.ruleAdapter.onCreateViewHolder(fragment.ruleListView, 1) as RouteFragment.RuleAdapter.RuleHolder
                holder.bind(rule)
                holder.enableSwitch.performClick()
            }
            drain()
            scenario.onActivity {
                assertFalse(fragment.ruleAdapter.ruleList.first { it.id == rule.id }.enabled)
            }
            assertEquals(rule.copy(enabled = false), SagerDatabase.rulesDao.getById(rule.id))
        }
    }

    @Test fun resetInvalidatesDetachedUndoAndNewPageMatchesDatabase() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fragment = route(scenario)
            val oldIds = SagerDatabase.rulesDao.allRules().map { it.id }.toSet()
            val release = heldQueue().first
            lateinit var staleUndo: View
            try {
                scenario.onActivity {
                    val index = 22
                    val removed = fragment.ruleAdapter.ruleList[index - 1]
                    fragment.ruleAdapter.remove(index)
                    fragment.undoManager.remove(index to removed)
                    val snackbar = UndoSnackbarManager::class.java.getDeclaredField("last")
                        .apply { isAccessible = true }.get(fragment.undoManager) as Snackbar
                    staleUndo = snackbar.view.findViewById(com.google.android.material.R.id.snackbar_action)
                    fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.action_reset_route))
                    val dialog = RouteFragment::class.java.getDeclaredField("resetDialog")
                        .apply { isAccessible = true }.get(fragment) as AlertDialog
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                // AlertDialog dispatches its positive listener through a Handler. Keep
                // storage held, but let confirmation invalidate the undo generation first.
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertTrue("Reset confirmation clears the visible list before storage runs",
                        fragment.ruleAdapter.ruleList.isEmpty())
                    staleUndo.performClick()
                    assertTrue("Detached undo cannot restore the previous generation",
                        fragment.ruleAdapter.ruleList.isEmpty())
                }
            } finally { release.complete(Unit) }
            drain()
            val currentIds = SagerDatabase.rulesDao.allRules().map { it.id }.toSet()
            assertTrue(currentIds.isNotEmpty())
            assertTrue(currentIds.intersect(oldIds).isEmpty())
            scenario.onActivity {
                staleUndo.performClick()
                assertEquals(currentIds, fragment.ruleAdapter.ruleList.map { it.id }.toSet())
                it.displayFragmentWithId(R.id.nav_group)
                it.supportFragmentManager.executePendingTransactions()
            }
            val recreated = route(scenario)
            scenario.onActivity { assertEquals(currentIds, recreated.ruleAdapter.ruleList.map { it.id }.toSet()) }
        }
    }

    @Test fun pendingDeleteCommitsOnViewDestroyAndForwardDragKeepsIdentitySet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fragment = route(scenario)
            val before = SagerDatabase.rulesDao.allRules().map { it.id }
            scenario.onActivity {
                fragment.ruleAdapter.move(20, 2)
                assertEquals(before.toSet(), fragment.ruleAdapter.ruleList.map { it.id }.toSet())
                assertEquals(before[19], fragment.ruleAdapter.ruleList[1].id)
                fragment.ruleAdapter.commitMove()
                val deleted = fragment.ruleAdapter.ruleList[20]
                fragment.ruleAdapter.remove(21)
                fragment.undoManager.remove(21 to deleted)
                it.displayFragmentWithId(R.id.nav_group)
                it.supportFragmentManager.executePendingTransactions()
            }
            drain()
            val records = SagerDatabase.rulesDao.allRules()
            assertEquals(23, records.size)
            assertEquals(before[19], records[1].id)
            val recreated = route(scenario)
            scenario.onActivity { assertEquals(records.map { it.id }, recreated.ruleAdapter.ruleList.map { it.id }) }
        }
    }

    @Test fun resetStorageFailureRestoresVisibleRules() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val fragment = route(scenario)
            val before = SagerDatabase.rulesDao.allRules().map { it.id }
            SagerDatabase.instance.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER vialen_list_test_failure BEFORE DELETE ON rules BEGIN SELECT RAISE(FAIL, 'Controlled reset failure'); END")
            scenario.onActivity {
                fragment.onMenuItemClick(fragment.toolbar.menu.findItem(R.id.action_reset_route))
                val dialog = RouteFragment::class.java.getDeclaredField("resetDialog")
                    .apply { isAccessible = true }.get(fragment) as AlertDialog
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            instrumentation.waitForIdleSync() // Let the dialog listener enqueue the reset before drain.
            drain()
            scenario.onActivity { assertEquals(before, fragment.ruleAdapter.ruleList.map { it.id }) }
            assertEquals(before, SagerDatabase.rulesDao.allRules().map { it.id })
        }
    }

    @Test fun dnsProgressPreservesScrollAndProfileDragRetainsEveryId() {
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "List scroll fixture"))
        try {
            val ids = (1..30).map { index ->
                SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = groupId, userOrder = index.toLong(),
                    socksBean = SOCKSBean().apply { initializeDefaultValues(); name = "Node $index"; serverAddress = "127.0.0.1"; serverPort = 1080 }))
            }
            DataStore.selectedGroup = groupId
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var child: ConfigurationFragment.GroupFragment
                val loaded = CountDownLatch(1)
                val handler = Handler(Looper.getMainLooper())
                lateinit var check: Runnable
                scenario.onActivity { activity ->
                    check = Runnable {
                        val candidate = (activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)?.getCurrentGroupFragment()
                        if (candidate?.adapter?.itemCount == 30) { child = candidate; loaded.countDown() }
                        else handler.postDelayed(check, 25)
                    }
                    handler.post(check)
                }
                try { assertTrue("Profile list loaded", loaded.await(15, TimeUnit.SECONDS)) }
                finally { handler.removeCallbacks(check) }
                scenario.onActivity { child.layoutManager.scrollToPositionWithOffset(15, 0) }
                instrumentation.waitForIdleSync()
                var first = -1
                scenario.onActivity { first = child.layoutManager.findFirstVisibleItemPosition() }
                GroupUpdater.updating.add(groupId)
                runBlocking { child.adapter!!.groupUpdated(groupId) }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(first, child.layoutManager.findFirstVisibleItemPosition())
                    val adapter = child.adapter!!
                    adapter.move(20, 2)
                    assertEquals(ids.toSet(), adapter.configurationIdList.toSet())
                    assertEquals(ids[20], adapter.configurationIdList[2])
                    adapter.commitMove()
                }
                GroupUpdater.updating.remove(groupId)
                val profileQueue = ConfigurationFragment.GroupFragment::class.java.getDeclaredField("profileWrites")
                    .apply { isAccessible = true }.get(null) as OrderedWorkQueue
                val done = CountDownLatch(1)
                profileQueue.submit { done.countDown() }
                assertTrue(done.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(ids.toSet(), SagerDatabase.proxyDao.getByGroup(groupId).map { it.id }.toSet())
                assertEquals(ids[20], SagerDatabase.proxyDao.getByGroup(groupId)[2].id)
            }
        } finally {
            GroupUpdater.updating.remove(groupId)
            SagerDatabase.proxyDao.deleteByGroup(groupId)
            SagerDatabase.groupDao.deleteById(groupId)
        }
    }

    @Test fun groupRebindClearsUnsupportedTrafficHeader() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.displayFragmentWithId(R.id.nav_group)
                it.supportFragmentManager.executePendingTransactions()
                val fragment = it.supportFragmentManager.findFragmentById(R.id.fragment_holder) as GroupFragment
                val holder = fragment.groupAdapter.onCreateViewHolder(fragment.groupListView, 0)
                val subscription = SubscriptionBean().apply { initializeDefaultValues(); subscriptionUserinfo = "upload=123; total=456" }
                val group = ProxyGroup(id = Long.MAX_VALUE, name = "Traffic fixture", type = GroupType.SUBSCRIPTION, subscription = subscription)
                holder.bind(group)
                assertEquals(View.VISIBLE, holder.groupTraffic.visibility)
                subscription.subscriptionUserinfo = "upload=0; download=0; total=0"
                holder.bind(group)
                assertEquals(View.GONE, holder.groupTraffic.visibility)
                assertEquals("", holder.groupTraffic.text.toString())
                fragment.groupAdapter.onViewRecycled(holder)
            }
        }
    }
}
