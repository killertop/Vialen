package io.nekohasekai.sagernet.group

import android.content.ContentResolver
import android.net.Uri
import io.mockk.*
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.RustBridgeRobolectricTestRunner
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RawUpdaterContentLeakTest {

    private class TrackingInputStream(
        bytes: ByteArray,
        private val throwOnRead: Boolean = false,
    ) : ByteArrayInputStream(bytes) {
        val closeCount = AtomicInteger(0)
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (throwOnRead) throw IOException("Simulated content stream read failure")
            return super.read(b, off, len)
        }
        override fun close() {
            closeCount.incrementAndGet()
            super.close()
        }
    }

    private val originalApp = runCatching { SagerNet.application }.getOrNull()
    private val mockApp = mockk<SagerNet>(relaxed = true)
    private val mockResolver = mockk<ContentResolver>(relaxed = true)
    private val mockDb = mockk<SagerDatabase>(relaxed = true)
    private val mockGroupDao = mockk<ProxyGroup.Dao>(relaxed = true)
    private val mockProxyDao = mockk<ProxyEntity.Dao>(relaxed = true)

    @Before
    fun setup() {
        SagerNet.application = mockApp
        every { mockApp.contentResolver } returns mockResolver
        every { mockApp.getString(io.nekohasekai.sagernet.R.string.no_proxies_found_in_subscription) } returns "No proxies found in subscription"

        mockkObject(SagerDatabase.Companion)
        every { SagerDatabase.instance } returns mockDb
        every { mockDb.groupDao() } returns mockGroupDao
        every { mockDb.proxyDao() } returns mockProxyDao
        every { mockDb.runInTransaction(any<Runnable>()) } answers {
            firstArg<Runnable>().run()
        }

        mockkObject(ProfileManager)
        every { ProfileManager.selectFirstIfNeeded(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(SagerDatabase.Companion)
        unmockkObject(ProfileManager)
        if (originalApp != null) {
            SagerNet.application = originalApp
        }
    }

    @Test
    fun contentStreamIsClosedExactlyOnceOnSuccessfulUpdate() = runBlocking {
        val testContent = "trojan://secret@node1.example:443#TestTrojan\n"
        val stream = TrackingInputStream(testContent.toByteArray())
        val uri = Uri.parse("content://io.nekohasekai.sagernet.cache/test-sub.txt")

        every { mockResolver.openInputStream(uri) } returns stream
        val sub = SubscriptionBean().apply {
            link = uri.toString()
            deduplication = false
            forceResolve = false
        }
        val group = ProxyGroup(id = 42L, name = "TestGroup", type = GroupType.SUBSCRIPTION, subscription = sub)
        every { mockGroupDao.getById(42L) } returns group
        every { mockProxyDao.getByGroup(42L) } returns emptyList()
        every { mockProxyDao.countByGroup(42L) } returns 1L

        RawUpdater.doUpdate(group, sub, null, false)

        assertEquals("InputStream must be closed exactly once after successful content update", 1, stream.closeCount.get())
    }

    @Test
    fun contentStreamIsClosedExactlyOnceOnReadFailure() = runBlocking {
        val stream = TrackingInputStream("ignored content".toByteArray(), throwOnRead = true)
        val uri = Uri.parse("content://io.nekohasekai.sagernet.cache/test-sub-fail.txt")

        every { mockResolver.openInputStream(uri) } returns stream
        val sub = SubscriptionBean().apply {
            link = uri.toString()
            deduplication = false
            forceResolve = false
        }
        val group = ProxyGroup(id = 43L, name = "TestFailGroup", type = GroupType.SUBSCRIPTION, subscription = sub)

        val error = runCatching {
            RawUpdater.doUpdate(group, sub, null, false)
        }.exceptionOrNull()

        assertNotNull("Expected IOException to be thrown on read error", error)
        assertTrue("Expected simulated read failure but got: $error", error is IOException)
        assertEquals("Simulated content stream read failure", error?.message)
        assertEquals("InputStream must be closed exactly once even when reading content throws", 1, stream.closeCount.get())
    }

    @Test
    fun contentStreamIsClosedExactlyOnceOnEmptyContentError() = runBlocking {
        val stream = TrackingInputStream("".toByteArray())
        val uri = Uri.parse("content://io.nekohasekai.sagernet.cache/empty.txt")

        every { mockResolver.openInputStream(uri) } returns stream
        val sub = SubscriptionBean().apply {
            link = uri.toString()
            deduplication = false
            forceResolve = false
        }
        val group = ProxyGroup(id = 44L, name = "EmptyGroup", type = GroupType.SUBSCRIPTION, subscription = sub)

        val error = runCatching {
            RawUpdater.doUpdate(group, sub, null, false)
        }.exceptionOrNull()

        assertNotNull("Expected IllegalStateException for empty content", error)
        assertTrue("Expected IllegalStateException but got: $error", error is IllegalStateException)
        assertEquals("No proxies found in subscription", error?.message)
        assertEquals("InputStream must be closed exactly once even when content contains no proxies", 1, stream.closeCount.get())
    }

    @Test
    fun nullStreamRetainsUnchangedFailureSemantics() = runBlocking {
        val uri = Uri.parse("content://io.nekohasekai.sagernet.cache/missing.txt")
        every { mockResolver.openInputStream(uri) } returns null
        val sub = SubscriptionBean().apply {
            link = uri.toString()
            deduplication = false
            forceResolve = false
        }
        val group = ProxyGroup(id = 45L, name = "MissingGroup", type = GroupType.SUBSCRIPTION, subscription = sub)

        val error = runCatching {
            RawUpdater.doUpdate(group, sub, null, false)
        }.exceptionOrNull()

        assertNotNull("Expected IllegalStateException for null stream", error)
        assertTrue("Expected IllegalStateException but got: $error", error is IllegalStateException)
        assertEquals("No proxies found in subscription", error?.message)
    }
}
