package io.nekohasekai.sagernet

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import libcore.Libcore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/** Real running core -> NB4A/NativeInterface -> TrafficLooper + Binder + Room.
 * Synthetic loopback destinations only; no Android service, main-core registration, or VPN.
 */
@RunWith(AndroidJUnit4::class)
class SelectorCallbackNativeTest {
    @get:Rule(order = Int.MIN_VALUE) val foreground = BenchmarkForegroundRule()
    @get:Rule val profileState = ProfileSelectionStateRule()

    private class FakeService : BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "SelectorCallbackNative"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null
        override fun createNotification(profileName: String): ServiceNotification = error("No notification")
        override fun acquireWakeLock() = Unit
    }

    private class Callback : ISagerNetServiceCallback.Stub() {
        val selected = AtomicLong(-1)
        override fun stateChanged(state: Int, profileName: String?, msg: String?) = Unit
        override fun cbSpeedUpdate(stats: SpeedDisplayData?) = Unit
        override fun cbTrafficUpdate(stats: TrafficData?) = Unit
        override fun cbSelectorUpdate(id: Long) { selected.set(id) }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(5000) {
        while (!predicate()) delay(10)
    }

    @Test fun liveSelectionDrainsOldNodeAndUpdatesBinderTitleWithoutOverwritingEdits() = runBlocking {
        val db = SagerDatabase.instance
        val preferences = PublicDatabase.instance
        val dao = preferences.keyValuePairDao()
        val before = linkedMapOf<String, KeyValuePair?>()
        preferences.runInTransaction {
            listOf(Key.PROFILE_TRAFFIC_STATISTICS, Key.SHOW_DIRECT_SPEED).forEach { key ->
                before[key] = dao[key]?.let { row -> KeyValuePair(row.key).also {
                    it.valueType = row.valueType; it.value = row.value.copyOf()
                } }
            }
        }
        val previousService = DataStore.baseService
        val previousState = DataStore.serviceState
        val service = FakeService()
        val callback = Callback()
        var instance: ProxyInstance? = null
        var groupId: Long? = null
        profileState.preservingFailure({
            check(previousService == null) { "Refuse to replace an active service" }
            DataStore.profileTrafficStatistics = true
            DataStore.showDirectSpeed = false
            val group = ProxyGroup(name = "selector-callback-${System.nanoTime()}", isSelector = true)
            group.id = db.groupDao().createGroup(group)
            groupId = group.id
            val nonceA = "selector-a-${System.nanoTime()}"
            val nonceB = "selector-b-${System.nanoTime()}"
            LoopbackSocksFixture(nonceA).use { fixtureA ->
                LoopbackSocksFixture(nonceB).use { fixtureB ->
                    fun row(name: String, port: Int, tx: Long, rx: Long) =
                        ProxyEntity(groupId = group.id, tx = tx, rx = rx).apply {
                            putBean(SOCKSBean().applyDefaultValues().apply {
                                this.name = name; serverAddress = "127.0.0.1"; serverPort = port
                            })
                            id = db.proxyDao().addProxy(this)
                        }
                    val a = row(nonceA, fixtureA.port, 11, 37)
                    val b = row(nonceB, fixtureB.port, 19, 43)
                    val tagA = "g-${a.id}"
                    val tagB = "g-${b.id}"
                    val config = """{"outbounds":[
                        {"type":"selector","tag":"$TAG_PROXY","outbounds":["$tagA","$tagB"],"default":"$tagA"},
                        {"type":"socks","tag":"$tagA","server":"127.0.0.1","server_port":${fixtureA.port}},
                        {"type":"socks","tag":"$tagB","server":"127.0.0.1","server_port":${fixtureB.port}},
                        {"type":"direct","tag":"$TAG_BYPASS"}],"route":{"final":"$TAG_PROXY"}}"""
                    val proxy = ProxyInstance(a, service)
                    instance = proxy
                    proxy.config = ConfigBuildResult(config, emptyList(), a.id,
                        linkedMapOf(tagA to listOf(a), tagB to listOf(b)),
                        mapOf(a.id to tagA, b.id to tagB), group.id)
                    proxy.box = Libcore.newSingBoxInstance(config, null)
                    service.data.proxy = proxy
                    service.data.state = BaseService.State.Connected
                    DataStore.baseService = service
                    service.data.binder.registerCallback(callback, SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
                    proxy.box.start()
                    val loop = TrafficLooper(service.data)
                    proxy.looper = loop
                    loop.start()
                    assertTrue(loop.isSelected(a.id))
                    assertTrue(withContext(Dispatchers.Default) {
                        Libcore.urlTest(proxy.box, "http://198.18.0.254/$nonceA", 4000)
                    } >= 0)
                    awaitCondition { fixtureA.diagnosticSnapshot().first().contains("active=0 ") }
                    assertEquals(2, fixtureA.requests.get())
                    assertEquals(0, fixtureB.requests.get())

                    val editedB = checkNotNull(db.proxyDao().getById(b.id)).apply {
                        userOrder = 79
                        requireBean().apply { name = "$nonceB-edited"; serverAddress = "edited.example.com" }
                    }
                    db.proxyDao().updateProxy(editedB)
                    assertTrue(proxy.box.selectOutbound(tagB))
                    // No direct selectMain invocation: only the native callback may change this.
                    assertTrue("Native callback did not synchronously change traffic owner", loop.isSelected(b.id))
                    awaitCondition { callback.selected.get() == b.id }
                    assertEquals(editedB.displayName(), ServiceNotification.genTitle(editedB))
                    assertEquals(editedB.displayName(), proxy.displayProfileName)
                    awaitCondition { db.proxyDao().getById(a.id)!!.tx > a.tx }
                    val savedA = checkNotNull(db.proxyDao().getById(a.id))
                    assertTrue(savedA.rx > a.rx)
                    assertEquals(b.tx, db.proxyDao().getById(b.id)!!.tx)

                    assertTrue(withContext(Dispatchers.Default) {
                        Libcore.urlTest(proxy.box, "http://198.18.0.254/$nonceB", 4000)
                    } >= 0)
                    awaitCondition { fixtureB.diagnosticSnapshot().first().contains("active=0 ") }
                    assertEquals(2, fixtureA.requests.get())
                    assertEquals(2, fixtureB.requests.get())
                    proxy.close(); instance = null
                    val finalA = checkNotNull(db.proxyDao().getById(a.id))
                    val finalB = checkNotNull(db.proxyDao().getById(b.id))
                    assertEquals(savedA.tx, finalA.tx); assertEquals(savedA.rx, finalA.rx)
                    assertTrue(finalB.tx > b.tx); assertTrue(finalB.rx > b.rx)
                    assertEquals(79L, finalB.userOrder)
                    assertEquals("$nonceB-edited", finalB.requireBean().name)
                    assertEquals("edited.example.com", finalB.requireBean().serverAddress)
                    println("SELECTOR_CALLBACK native=true requests_a=2 requests_b=2 binder_id=${b.id} title_updated=true separate_traffic=true edit_preserved=true")
                }
            }
        }, {
            profileState.cleanupSteps({ instance?.close(); instance = null }, {
                service.data.proxy = null
                service.data.binder.close()
                DataStore.baseService = previousService
                DataStore.serviceState = previousState
            }, {
                groupId?.let { id ->
                    db.runInTransaction { db.proxyDao().deleteByGroup(id); db.groupDao().deleteById(id) }
                    assertNull(db.groupDao().getById(id))
                    assertTrue(db.proxyDao().getByGroup(id).isEmpty())
                }
            }, {
                preferences.runInTransaction {
                    before.forEach { (key, row) -> if (row == null) dao.delete(key) else dao.put(row) }
                }
                before.forEach { (key, row) ->
                    val restored = dao[key]
                    assertTrue("Preference restore failed: $key", if (row == null) restored == null else
                        restored != null && row.valueType == restored.valueType && row.value.contentEquals(restored.value))
                }
            })
        })
    }
}
