package io.nekohasekai.sagernet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual native HTTP and Room; no GroupFragment or production subscription is involved. */
@RunWith(AndroidJUnit4::class)
class SubscriptionCreationNativeTest {
    @get:Rule val profileState = ProfileSelectionStateRule()

    @Test fun allDefaultRulesHaveValidOfflineBootstrap() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val rules = DefaultRouteRules.create(context::getString)
        assertEquals(listOf(true, true, true, true), rules.map { it.enabled })
        for (rule in rules) {
            val ref = RouteRuleSet.decode(rule.ruleSets).single()
            val file = requireNotNull(BundledRuleSets.prepare(context.filesDir, ref, context.assets::open))
            libcore.Libcore.validateRuleSet(file.absolutePath, ref.format)
            assertTrue(file.length() > 0)
        }
    }

    @Test fun creatingSubscriptionFetchesWithoutGroupPageAndEmptyRefreshKeepsNodes() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug"))
        val previousUi = GroupManager.userInterface
        val success = AtomicInteger()
        val failure = AtomicReference<String>()
        val body = AtomicReference("socks://127.0.0.1:10080#Local%20subscription%20fixture")
        val requests = AtomicInteger()
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = body.get().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                    requests.incrementAndGet()
                }
            } catch (_: java.net.SocketException) { }
        }
        var groupId: Long? = null
        try {
            GroupManager.userInterface = object : GroupManager.Interface {
                override suspend fun confirm(message: String) = true
                override suspend fun alert(message: String) = Unit
                override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) { success.incrementAndGet() }
                override suspend fun onUpdateFailure(group: ProxyGroup, message: String) { failure.set(message) }
            }
            val group = GroupManager.createGroup(ProxyGroup(name = "subscription-native-${System.nanoTime()}", type = GroupType.SUBSCRIPTION,
                subscription = SubscriptionBean().applyDefaultValues().apply {
                    link = "http://127.0.0.1:${server.localPort}/subscription"
                    autoUpdate = false
                    updateWhenConnectedOnly = false
                }))
            groupId = group.id
            withTimeout(15000) { while (success.get() == 0 || group.id in GroupUpdater.updating) delay(25) }
            assertNull(failure.get())
            val original = SagerDatabase.proxyDao.getByGroup(group.id).map { it.id }
            assertEquals(1, original.size)
            body.set("")
            assertFalse(GroupUpdater.executeUpdate(group, true))
            assertNotNull(failure.get())
            assertTrue(failure.get().any { it in '\u4e00'..'\u9fff' })
            assertEquals(original, SagerDatabase.proxyDao.getByGroup(group.id).map { it.id })
            assertEquals(2, requests.get())
        } finally {
            groupId?.let { id ->
                withTimeout(35000) { while (id in GroupUpdater.updating) delay(25) }
                SagerDatabase.proxyDao.deleteAll(id)
                SagerDatabase.groupDao.deleteById(id)
            }
            GroupManager.userInterface = previousUi
            server.close(); worker.join(5000)
        }
    }
}
