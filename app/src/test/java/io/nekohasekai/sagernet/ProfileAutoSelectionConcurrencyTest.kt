package io.nekohasekai.sagernet

import kotlinx.coroutines.launch
import io.mockk.coEvery
import androidx.room.Room
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import moe.matsuri.nb4a.TempDatabase
import org.junit.AfterClass
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(ProfileSelectionRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileAutoSelectionConcurrencyTest {
    companion object {
        private const val TARGET = 10L
        private const val FIRST = 101L
        private const val MANUAL = 202L
        private lateinit var preferences: PublicDatabase
        private lateinit var profiles: ProxyEntity.Dao
        private lateinit var profileDatabase: SagerDatabase

        private fun ensureDatabase() {
            if (!::preferences.isInitialized) setUpDatabase()
        }

        private fun setUpDatabase() {
            preferences = Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(), PublicDatabase::class.java
            ).allowMainThreadQueries().build()
            profiles = mockk()
            profileDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
                .allowMainThreadQueries().build()
            mockkObject(PublicDatabase.Companion, TempDatabase.Companion, SagerDatabase.Companion)
            every { PublicDatabase.instance } returns preferences
            every { PublicDatabase.kvPairDao } returns preferences.keyValuePairDao()
            every { TempDatabase.profileCacheDao } returns preferences.keyValuePairDao()
            every { SagerDatabase.proxyDao } returns profiles
            every { SagerDatabase.instance } returns profileDatabase
            // DataStore and its selectedProxy delegate are real, backed by Room/SQLite.
            // Only profile lookup is mocked to make the race deterministic.
            DataStore.selectedProxy = 0L
        }

        @AfterClass @JvmStatic fun closeDatabase() {
            if (::preferences.isInitialized) preferences.close()
            if (::profileDatabase.isInitialized) profileDatabase.close()
            unmockkObject(PublicDatabase.Companion, TempDatabase.Companion, SagerDatabase.Companion)
        }
    }

    @Before fun reset() {
        // Robolectric has installed the Application by @Before, but not @BeforeClass.
        ensureDatabase()
        preferences.keyValuePairDao().reset()
        // Prove both directions use the actual Room DAO, not a stale mocked delegate.
        DataStore.selectedProxy = MANUAL
        assertEquals(MANUAL, preferences.keyValuePairDao()[Key.PROFILE_ID]?.long)
        preferences.keyValuePairDao().put(
            io.nekohasekai.sagernet.database.preference.KeyValuePair(Key.PROFILE_ID).put(FIRST)
        )
        assertEquals(FIRST, DataStore.selectedProxy)
        DataStore.selectedProxy = 0L
        clearMocks(profiles)
        profileDatabase.clearAllTables()
        DataStore.serviceState = BaseService.State.Stopped
        every { profiles.getIdsByGroup(TARGET) } returns listOf(FIRST)
    }

    @Test fun clearUnrelatedGroupPreservesSelectionAndClearsOnlyTargetRows() = kotlinx.coroutines.runBlocking {
        clearFixture()
        io.nekohasekai.sagernet.database.GroupManager.clearGroup(20)
        assertEquals(FIRST, DataStore.selectedProxy)
        assertEquals(listOf(FIRST), profileDatabase.proxyDao().getAll().map { it.id })
    }

    @Test fun clearSelectedGroupClearsSelectionAfterSuccessfulDelete() = kotlinx.coroutines.runBlocking {
        clearFixture()
        io.nekohasekai.sagernet.database.GroupManager.clearGroup(TARGET)
        assertEquals(0L, DataStore.selectedProxy)
        assertEquals(listOf(MANUAL), profileDatabase.proxyDao().getAll().map { it.id })
    }

    @Test fun failedGroupDeletionPreservesRowsAndSelection() = kotlinx.coroutines.runBlocking {
        clearFixture()
        profileDatabase.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_clear BEFORE DELETE ON proxy_entities BEGIN SELECT RAISE(ABORT, 'synthetic deletion failure'); END")
        try {
            org.junit.Assert.assertTrue(runCatching { io.nekohasekai.sagernet.database.GroupManager.clearGroup(TARGET) }.isFailure)
            assertEquals(FIRST, DataStore.selectedProxy)
            assertEquals(2, profileDatabase.proxyDao().getAll().size)
        } finally { profileDatabase.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_clear") }
    }

    @Test fun selectionChangedDuringDeletionIsNeverCleared() = kotlinx.coroutines.runBlocking {
        clearFixture()
        every { profiles.deleteAll(TARGET) } answers {
            DataStore.selectedProxy = MANUAL
            profileDatabase.proxyDao().deleteAll(TARGET)
        }
        io.nekohasekai.sagernet.database.GroupManager.clearGroup(TARGET)
        assertEquals(MANUAL, DataStore.selectedProxy)
        assertEquals(listOf(MANUAL), profileDatabase.proxyDao().getAll().map { it.id })
    }

    private fun clearFixture() {
        fun row(id: Long, group: Long) = ProxyEntity(id = id, groupId = group).putProfile(
            io.nekohasekai.sagernet.core.Profile(type = "socks", server = "example.test", port = 1080,
                socks = io.nekohasekai.sagernet.core.Profile.Socks()))
        profileDatabase.proxyDao().addProxy(row(FIRST, TARGET))
        profileDatabase.proxyDao().addProxy(row(MANUAL, 20))
        every { profiles.getById(any()) } answers { profileDatabase.proxyDao().getById(firstArg()) }
        every { profiles.deleteAll(any()) } answers { profileDatabase.proxyDao().deleteAll(firstArg()) }
        DataStore.selectedProxy = FIRST
    }

    @Test fun absentAndInvalidSelectionsPickTheFirstCandidate() {
        ProfileManager.selectFirstIfNeeded(TARGET)
        assertEquals(FIRST, DataStore.selectedProxy)
        DataStore.selectedProxy = 999L
        every { profiles.getById(999L) } returns null
        ProfileManager.selectFirstIfNeeded(TARGET)
        assertEquals(FIRST, DataStore.selectedProxy)
    }

    @Test fun validSelectionIsPreservedWithoutCandidateLookup() {
        DataStore.selectedProxy = MANUAL
        every { profiles.getById(MANUAL) } returns ProxyEntity(id = MANUAL)
        ProfileManager.selectFirstIfNeeded(TARGET)
        assertEquals(MANUAL, DataStore.selectedProxy)
        verify(exactly = 0) { profiles.getIdsByGroup(any()) }
    }

    @Test fun emptyGroupDoesNotCreateASelection() {
        every { profiles.getIdsByGroup(TARGET) } returns emptyList()
        ProfileManager.selectFirstIfNeeded(TARGET)
        assertEquals(0L, DataStore.selectedProxy)
    }

    @Test fun deletedRunningSelectionIsDeferredUntilStopped() {
        DataStore.selectedProxy = 999L
        every { profiles.getById(999L) } returns null
        DataStore.serviceState = BaseService.State.Connected
        ProfileManager.selectFirstIfNeeded(TARGET)
        assertEquals(999L, DataStore.selectedProxy)
        assertEquals(TARGET, DataStore.pendingSelectionGroup)
        verify(exactly = 0) { profiles.getIdsByGroup(any()) }
        DataStore.serviceState = BaseService.State.Stopped
        ProfileManager.selectFirstIfNeeded(DataStore.pendingSelectionGroup)
        assertEquals(FIRST, DataStore.selectedProxy)
        assertEquals(0L, DataStore.pendingSelectionGroup)
    }

    @Test fun validManualSelectionDiscardsAnOlderDeferredGroup() {
        DataStore.pendingSelectionGroup = TARGET
        DataStore.selectedProxy = MANUAL
        every { profiles.getById(MANUAL) } returns ProxyEntity(id = MANUAL)
        DataStore.serviceState = BaseService.State.Connected
        ProfileManager.selectFirstIfNeeded(TARGET)
        assertEquals(MANUAL, DataStore.selectedProxy)
        assertEquals(0L, DataStore.pendingSelectionGroup)
        verify(exactly = 0) { profiles.getIdsByGroup(any()) }
    }

    private fun whileCandidateLookupIsBlocked(action: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        every { profiles.getIdsByGroup(TARGET) } answers {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "candidate lookup release timed out" }
            listOf(FIRST)
        }
        try {
            val automatic = worker.submit { ProfileManager.selectFirstIfNeeded(TARGET) }
            assertTrue("candidate lookup was not reached", entered.await(10, TimeUnit.SECONDS))
            action()
            release.countDown()
            automatic.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.shutdownNow()
            worker.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test fun laterManualSelectionSurvivesBlockedCandidateLookup() {
        whileCandidateLookupIsBlocked {
            // Same real preference setter used by the profile click handler. This must
            // complete before releasing the query: no selection lock may cover lookup.
            DataStore.selectedProxy = MANUAL
            assertEquals(MANUAL, DataStore.selectedProxy)
        }
        assertEquals(MANUAL, DataStore.selectedProxy)
    }

    @Test fun serviceStartingDuringLookupPreventsAutomaticSelection() {
        for (state in listOf(BaseService.State.Connecting, BaseService.State.Connected, BaseService.State.Stopping)) {
            DataStore.serviceState = BaseService.State.Stopped
            whileCandidateLookupIsBlocked { DataStore.serviceState = state }
            assertEquals("state=$state", 0L, DataStore.selectedProxy)
        }
    }

    @Test fun batchDeletionUsesSeparateConditionalSelectionTransactionAndCommittedEvents() = kotlinx.coroutines.runBlocking {
        val dao = profileDatabase.proxyDao()
        val first = dao.addProxy(ProxyEntity(groupId = 10, userOrder = 4))
        val unrelated = dao.addProxy(ProxyEntity(groupId = 20, userOrder = 8))
        val manual = dao.addProxy(ProxyEntity(groupId = 30, userOrder = 9))
        DataStore.selectedProxy = first
        every { SagerDatabase.proxyDao } returns dao
        val events = mutableListOf<Long>()
        val listener = mockk<ProfileManager.Listener>(relaxed = true)
        coEvery { listener.onRemoved(any(), any()) } coAnswers {
            val id = secondArg<Long>()
            org.junit.Assert.assertNull(dao.getById(id))
            events.add(id)
        }
        ProfileManager.addListener(listener)
        try {
            ProfileManager.deleteProfile(20, unrelated)
            assertEquals(first, DataStore.selectedProxy)
            // The profile transaction commits before the separate preferences transaction.
            val switchingDao = object : ProxyEntity.Dao by dao {
                override fun deleteProfiles(requests: List<io.nekohasekai.sagernet.database.ProfileDeletion>): List<io.nekohasekai.sagernet.database.ProfileDeletion> {
                    val removed = dao.deleteProfiles(requests)
                    DataStore.selectedProxy = manual
                    return removed
                }
            }
            every { SagerDatabase.proxyDao } returns switchingDao
            ProfileManager.deleteProfile(10, first)
            assertEquals(manual, DataStore.selectedProxy)
            every { SagerDatabase.proxyDao } returns dao
            ProfileManager.deleteProfile(30, manual)
            assertEquals(0L, DataStore.selectedProxy)
            assertEquals(listOf(unrelated, first, manual), events)
        } finally {
            ProfileManager.removeListener(listener)
            every { SagerDatabase.proxyDao } returns profiles
        }
    }

    @Test fun acceptedDeletionFinishesSelectionAndEventsWhenCallerCancels() = kotlinx.coroutines.runBlocking {
        val dao = profileDatabase.proxyDao()
        val id = dao.addProxy(ProxyEntity(groupId = 10, userOrder = 4))
        DataStore.selectedProxy = id
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        every { SagerDatabase.proxyDao } returns object : ProxyEntity.Dao by dao {
            override fun deleteProfiles(requests: List<io.nekohasekai.sagernet.database.ProfileDeletion>): List<io.nekohasekai.sagernet.database.ProfileDeletion> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return dao.deleteProfiles(requests)
            }
        }
        val event = CountDownLatch(1)
        val listener = mockk<ProfileManager.Listener>(relaxed = true)
        coEvery { listener.onRemoved(10, id) } coAnswers { event.countDown() }
        ProfileManager.addListener(listener)
        val task = launch(kotlinx.coroutines.Dispatchers.IO) {
            ProfileManager.deleteProfile(10, id)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            task.cancel()
        } finally { release.countDown() }
        try {
            task.join()
            org.junit.Assert.assertNull(dao.getById(id))
            assertEquals(0L, DataStore.selectedProxy)
            assertEquals(0L, event.count)
        } finally {
            ProfileManager.removeListener(listener)
            every { SagerDatabase.proxyDao } returns profiles
        }
    }
}
