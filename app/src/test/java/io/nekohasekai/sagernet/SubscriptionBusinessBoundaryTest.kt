package io.nekohasekai.sagernet

import androidx.room.Room
import android.net.Uri
import android.content.UriPermission
import io.mockk.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SubscriptionBusinessBoundaryTest {
    private lateinit var db: SagerDatabase
    private lateinit var resolver: android.content.ContentResolver
    private fun group(link: String) = ProxyGroup(name = "synthetic", type = GroupType.SUBSCRIPTION,
        subscription = SubscriptionBean().apply { initializeDefaultValues(); this.link = link })
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java).allowMainThreadQueries().build()
        mockkObject(SagerDatabase.Companion, SubscriptionUpdater, GroupUpdater.Companion, SagerNet.Companion)
        every { SagerDatabase.instance } returns db
        every { SagerDatabase.groupDao } returns db.groupDao()
        coEvery { SubscriptionUpdater.reconfigureUpdater() } just Runs
        every { GroupUpdater.startUpdate(any(), any()) } just Runs
        val app = mockk<SagerNet>()
        resolver = mockk()
        every { SagerNet.application } returns app
        every { app.contentResolver } returns resolver
        every { resolver.persistedUriPermissions } returns emptyList()
    }
    @After fun close() { unmockkAll(); db.close() }

    @Test fun invalidInputsCannotCreateOrReplaceRecordsAtTheSharedEntry() = runBlocking {
        val saved = GroupManager.createGroup(group("https://example.test/sub"))
        for (raw in listOf("", "file:///synthetic", "vless://synthetic@example.test", "https://user:synthetic@example.test", "https://example.test:0", "https://example.test:65536", "content://synthetic/new")) {
            assertTrue(runCatching { GroupManager.createGroup(group(raw)) }.isFailure)
            assertTrue(runCatching { GroupManager.updateGroup(group(raw).apply { id = saved.id }) }.isFailure)
            assertEquals(1, db.groupDao().allGroups().size)
            assertEquals("https://example.test/sub", db.groupDao().getById(saved.id)!!.subscription!!.link)
        }
    }

    @Test fun webPolicyAndTokenEncodingAreIdenticalForCreateAndEdit() = runBlocking {
        for ((raw, expected) in listOf(
            "http://example.test/a%2fb?q=a%2B%2f" to "http://example.test/a%2fb?q=a%2B%2f",
            "https://例子.中国:8443/a%2fb?q=a%2B%2f" to "https://xn--fsqu00a.xn--fiqs8s:8443/a%2fb?q=a%2B%2f",
        )) {
            val added = GroupManager.createGroup(group(raw))
            assertEquals(expected, db.groupDao().getById(added.id)!!.subscription!!.link)
            GroupManager.updateGroup(group(raw).apply { id = added.id })
            assertEquals(expected, db.groupDao().getById(added.id)!!.subscription!!.link)
        }
    }

    @Test fun legacyDocumentsRequireBothStoredIdentityAndPersistedReadGrant() = runBlocking {
        val uri = Uri.parse("content://synthetic/old")
        val old = group(uri.toString()).apply { id = db.groupDao().createGroup(this) }
        val grant = mockk<UriPermission>()
        every { grant.uri } returns uri
        every { grant.isReadPermission } returns true
        assertTrue(runCatching { GroupManager.updateGroup(old.copy(name = "denied")) }.isFailure)
        every { resolver.persistedUriPermissions } returns listOf(grant)
        GroupManager.updateGroup(old.copy(name = "allowed"))
        assertEquals("allowed", db.groupDao().getById(old.id)!!.name)
        assertTrue(runCatching { GroupManager.createGroup(group(uri.toString())) }.isFailure)
        assertTrue(runCatching { GroupManager.updateGroup(group("content://synthetic/other").apply { id = old.id }) }.isFailure)
        every { grant.isReadPermission } returns false
        assertTrue(runCatching { GroupManager.updateGroup(old.copy(name = "revoked")) }.isFailure)
        assertEquals("allowed", db.groupDao().getById(old.id)!!.name)
    }
}
