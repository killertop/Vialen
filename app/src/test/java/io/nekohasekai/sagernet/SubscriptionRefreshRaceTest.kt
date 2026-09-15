package io.nekohasekai.sagernet

import androidx.room.Room
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.SubscriptionPersistence
import io.nekohasekai.sagernet.group.SubscriptionRefresh
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SubscriptionRefreshRaceTest {
    private lateinit var db: SagerDatabase
    private lateinit var peer: SagerDatabase
    private lateinit var group: ProxyGroup
    private fun node(name: String) = Profile(name = name, type = "socks", server = "example.test", port = 1080, socks = Profile.Socks())

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val name = "subscription-race-${System.nanoTime()}"
        db = Room.databaseBuilder(context, SagerDatabase::class.java, name).allowMainThreadQueries().setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        peer = Room.databaseBuilder(context, SagerDatabase::class.java, name).allowMainThreadQueries().setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        group = ProxyGroup(name = "saved", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().applyDefaultValues().apply { link = "https://example.test/a"; autoUpdate = true })
        group.id = db.groupDao().createGroup(group)
        db.proxyDao().addProxy(ProxyEntity(groupId = group.id).putProfile(node("original")))
    }
    @After fun close() { peer.close(); db.close() }
    private fun assertOriginal() = assertEquals(listOf("original"), db.proxyDao().getByGroup(group.id).map { it.requireProfile().name })
    private suspend fun stale(ticket: SubscriptionRefresh.Ticket) {
        try { SubscriptionPersistence.apply(db, ticket, listOf(node("stale"))); fail("stale result committed") }
        catch (_: SubscriptionRefresh.Stale) { }
    }

    @Test fun delayedDownloadCannotOverwriteLinkNameOrDisabledAutomation() = runBlocking {
        for (change in listOf<(ProxyGroup) -> Unit>(
            { it.subscription!!.link = "https://example.test/b" },
            { it.subscription!!.autoUpdate = false },
            { it.name = "renamed" },
            { it.subscription!!.deduplication = true },
        )) {
            val ticket = SubscriptionRefresh.begin(db, group.id)
            val edited = peer.groupDao().getById(group.id)!!
            change(edited); peer.groupDao().updateGroup(edited)
            stale(ticket); assertOriginal()
            val saved = db.groupDao().getById(group.id)!!
            assertEquals(edited.name, saved.name)
            assertEquals(edited.subscription!!.link, saved.subscription!!.link)
            assertEquals(edited.subscription!!.autoUpdate, saved.subscription!!.autoUpdate)
        }
    }

    @Test fun configurationAbaAlsoInvalidatesAnOldResult() = runBlocking {
        val ticket = SubscriptionRefresh.begin(db, group.id)
        val edited = peer.groupDao().getById(group.id)!!
        edited.subscription!!.link = "https://example.test/b"; peer.groupDao().updateGroup(edited)
        edited.subscription!!.link = "https://example.test/a"; peer.groupDao().updateGroup(edited)
        stale(ticket); assertOriginal()
    }

    @Test fun separateConnectionsRejectOutOfOrderSameConfigurationResults() = runBlocking {
        val older = SubscriptionRefresh.begin(db, group.id, requireAutoUpdate = true)
        val newer = withContext(Dispatchers.IO) { SubscriptionRefresh.begin(peer, group.id) }
        assertEquals(older.configVersion, newer.configVersion)
        assertTrue(newer.generation > older.generation)
        SubscriptionPersistence.apply(peer, newer, listOf(node("newest")), "remote metadata")
        stale(older)
        assertEquals(listOf("newest"), db.proxyDao().getByGroup(group.id).map { it.requireProfile().name })
        assertEquals("remote metadata", db.groupDao().getById(group.id)!!.subscription!!.subscriptionUserinfo)
    }

    @Test fun deletionRejectsResultAndCascadesCoordinationState() = runBlocking {
        val ticket = SubscriptionRefresh.begin(db, group.id)
        peer.runInTransaction { peer.proxyDao().deleteByGroup(group.id); peer.groupDao().deleteById(group.id) }
        stale(ticket)
        assertEquals(0, db.proxyDao().getByGroup(group.id).size)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM subscription_refresh_state").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }

    @Test fun failedOrCancelledNewestRequestNeverReleasesAnOlderResult() = runBlocking {
        val old = SubscriptionRefresh.begin(db, group.id)
        SubscriptionRefresh.begin(peer, group.id) // Network fails: no commit, old remains superseded.
        stale(old); assertOriginal()
        val latest = SubscriptionRefresh.begin(db, group.id)
        val cancelled = launch(start = CoroutineStart.LAZY) { SubscriptionPersistence.apply(db, latest, listOf(node("cancelled"))) }
        cancelled.cancel(); cancelled.join(); assertOriginal()
        val retry = SubscriptionRefresh.begin(peer, group.id)
        SubscriptionPersistence.apply(peer, retry, listOf(node("retry")))
        assertEquals("retry", db.proxyDao().getByGroup(group.id).single().requireProfile().name)
    }

    @Test fun parallelManualAndAutomaticResponsesCommitOnlyTheNewGeneration() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val releaseOldResponse = CompletableDeferred<Unit>()
        val automatic = async(Dispatchers.IO) {
            val ticket = SubscriptionRefresh.begin(db, group.id, requireAutoUpdate = true)
            requested.complete(Unit)
            releaseOldResponse.await() // Controlled delayed response; no transaction is held.
            runCatching { SubscriptionPersistence.apply(db, ticket, listOf(node("old response"))) }.exceptionOrNull()
        }
        requested.await()
        val manual = SubscriptionRefresh.begin(peer, group.id)
        SubscriptionPersistence.apply(peer, manual, listOf(node("manual response")))
        releaseOldResponse.complete(Unit)
        assertTrue(automatic.await() is SubscriptionRefresh.Stale)
        assertEquals("manual response", db.proxyDao().getByGroup(group.id).single().requireProfile().name)
        val edited = peer.groupDao().getById(group.id)!!
        edited.subscription!!.autoUpdate = false
        peer.groupDao().updateGroup(edited)
        assertTrue(runCatching { SubscriptionRefresh.begin(db, group.id, requireAutoUpdate = true) }.exceptionOrNull() is SubscriptionRefresh.Stale)
    }

    @Test fun refreshUsesFreshTrafficCountersAndPreservesCheckpointAndResetOrder() = runBlocking {
        val original = db.proxyDao().getByGroup(group.id).single()
        original.sourceKey = io.nekohasekai.sagernet.group.SubscriptionPersistence.sourceKey(node("original"))
        db.proxyDao().updateProxy(original)
        val ticket = SubscriptionRefresh.begin(db, group.id)
        peer.proxyDao().addTraffic(original.id, 123, 456)
        SubscriptionPersistence.apply(db, ticket, listOf(node("original").copy(port = 1081)))
        assertEquals(123L, db.proxyDao().getById(original.id)!!.tx)
        assertEquals(456L, db.proxyDao().getById(original.id)!!.rx)
        peer.proxyDao().addTraffic(original.id, 10, 20)
        assertEquals(133L, db.proxyDao().getById(original.id)!!.tx)
        val next = SubscriptionRefresh.begin(db, group.id)
        peer.openHelper.writableDatabase.execSQL("UPDATE proxy_entities SET tx = 0, rx = 0 WHERE id = ?", arrayOf(original.id))
        SubscriptionPersistence.apply(db, next, listOf(node("original").copy(port = 1082)))
        assertEquals(0L, db.proxyDao().getById(original.id)!!.tx)
        assertEquals(0L, db.proxyDao().getById(original.id)!!.rx)
    }

    @Test fun mixedDependentBatchCannotReplacePreviouslySavedSubscription() = runBlocking {
        val ticket = SubscriptionRefresh.begin(db, group.id)
        val failure = runCatching {
            val profiles = io.nekohasekai.sagernet.group.RawUpdater.parseRaw(
                "proxies: [{type: socks5, server: example.test, port: 1080}, {type: socks5, server: example.test, port: 1081, dialer-proxy: synthetic-private}]",
                showWarnings = false)
            SubscriptionPersistence.apply(db, ticket, profiles)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure!!.message.orEmpty().contains("synthetic-private"))
        assertOriginal()
        assertEquals(0, db.groupDao().getById(group.id)!!.subscription!!.lastUpdated)
    }
    @Test fun obsoleteQueuedConfigurationCannotInvalidateNewerRefresh() = runBlocking {
        val queued = io.nekohasekai.sagernet.bg.SubscriptionSchedule.fingerprint(group.subscription!!)
        val edited = peer.groupDao().getById(group.id)!!
        edited.subscription!!.link = "content://synthetic/new-source"
        peer.groupDao().updateGroup(edited)
        val current = SubscriptionRefresh.begin(peer, group.id, requireAutoUpdate = true)
        val obsolete = runCatching {
            SubscriptionRefresh.begin(db, group.id, requireAutoUpdate = true, expectedConfig = queued)
        }.exceptionOrNull()
        assertTrue(obsolete is SubscriptionRefresh.Stale)
        SubscriptionPersistence.apply(peer, current, listOf(node("current")))
        assertEquals("current", db.proxyDao().getByGroup(group.id).single().requireProfile().name)
        assertEquals("content://synthetic/new-source", db.groupDao().getById(group.id)!!.subscription!!.link)
    }

}
