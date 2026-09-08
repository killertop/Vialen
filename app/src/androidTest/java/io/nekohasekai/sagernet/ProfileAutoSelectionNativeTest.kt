package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAutoSelectionNativeTest {
    @get:Rule val selectionState = ProfileSelectionStateRule()
    private val groups = mutableListOf<Long>()
    private var oldState = BaseService.State.Idle

    @Before fun setup() {
        oldState = DataStore.serviceState
        check(oldState == BaseService.State.Idle || oldState == BaseService.State.Stopped)
        DataStore.selectedProxy = 0L
    }

    @After fun cleanup() {
        groups.forEach {
            SagerDatabase.proxyDao.deleteByGroup(it)
            SagerDatabase.groupDao.deleteById(it)
        }
        DataStore.serviceState = oldState
        // The outer rule restores the raw selection rows after this teardown.
    }

    private fun group(): Long = SagerDatabase.groupDao.createGroup(
        ProxyGroup(name = "auto-selection-regression")
    ).also { groups.add(it) }

    private fun bean() = TrojanBean().apply {
        initializeDefaultValues(); serverAddress = "example.test"; serverPort = 443
    }

    @Test fun firstAddSelectsBeforeListenerAndSecondAddPreservesChoice() = runBlocking {
        val group = group()
        var selectedAtAdd = 0L
        val listener = object : ProfileManager.Listener {
            override suspend fun onAdd(profile: ProxyEntity) { selectedAtAdd = DataStore.selectedProxy }
            override suspend fun onUpdated(data: io.nekohasekai.sagernet.aidl.TrafficData) {}
            override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {}
            override suspend fun onRemoved(groupId: Long, profileId: Long) {}
        }
        ProfileManager.addListener(listener)
        try {
            val first = ProfileManager.createProfile(group, bean())
            assertEquals(first.id, selectedAtAdd)
            val second = ProfileManager.createProfile(group, bean())
            assertEquals(first.id, DataStore.selectedProxy)
            ProfileManager.deleteProfile(group, first.id)
            assertEquals(0L, DataStore.selectedProxy)
            ProfileManager.createProfile(group, bean())
            assertEquals(second.id, DataStore.selectedProxy)
        } finally { ProfileManager.removeListener(listener) }
    }

    @Test fun subscriptionPersistenceThenSelectionKeepsFirstAcrossRefresh() {
        val target = group()
        val group = SagerDatabase.groupDao.getById(target)!!
        fun named(name: String) = bean().apply { this.name = name }
        io.nekohasekai.sagernet.group.SubscriptionPersistence.apply(
            SagerDatabase.instance, group, listOf(named("first"), named("second"))
        )
        ProfileManager.selectFirstIfNeeded(target)
        val first = SagerDatabase.proxyDao.getIdsByGroup(target).first()
        assertEquals(first, DataStore.selectedProxy)
        io.nekohasekai.sagernet.group.SubscriptionPersistence.apply(
            SagerDatabase.instance, group, listOf(named("new"), named("second"), named("first"))
        )
        ProfileManager.selectFirstIfNeeded(target)
        assertEquals(first, DataStore.selectedProxy)
    }

    @Test fun fallbackStaysInTargetGroupAndRetainsValidOtherGroupSelection() = runBlocking {
        val other = ProfileManager.createProfile(group(), bean())
        val target = group()
        val first = ProfileManager.createProfile(target, bean())
        assertEquals(other.id, DataStore.selectedProxy)
        DataStore.selectedProxy = Long.MAX_VALUE
        ProfileManager.selectFirstIfNeeded(target)
        assertEquals(first.id, DataStore.selectedProxy)
        DataStore.selectedProxy = 0L
        ProfileManager.selectFirstIfNeeded(group())
        assertEquals(0L, DataStore.selectedProxy)
        listOf(BaseService.State.Connecting, BaseService.State.Connected, BaseService.State.Stopping).forEach {
            DataStore.serviceState = it
            ProfileManager.selectFirstIfNeeded(target)
            assertEquals(0L, DataStore.selectedProxy)
        }
    }
}
