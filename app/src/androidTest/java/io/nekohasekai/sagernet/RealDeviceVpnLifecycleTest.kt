package io.nekohasekai.sagernet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RealDeviceVpnLifecycleTest {

    private lateinit var profile1: ProxyEntity
    private lateinit var profile2: ProxyEntity
    private lateinit var connection: SagerConnection
    private var ssHost = "192.168.0.52"

    @Volatile
    private var callbackState: BaseService.State = BaseService.State.Idle

    private val callback = object : SagerConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            println("[VPN CALLBACK] stateChanged: $state (profile: $profileName, msg: $msg)")
            callbackState = state
        }

        override fun onServiceConnected(service: ISagerNetService) {
            println("[VPN SERVICE] onServiceConnected")
            connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        }
    }

    data class StateObservation(
        val stage: String,
        val expected: BaseService.State,
        val actualState: BaseService.State,
        val source: String,
        val elapsedMs: Long,
    )

    private suspend fun awaitProductionState(
        stage: String,
        expected: BaseService.State,
        timeoutMs: Long = 10_000
    ): StateObservation {
        val start = System.currentTimeMillis()
        var lastState: BaseService.State = BaseService.State.Idle
        var lastSource = "none"

        while (System.currentTimeMillis() - start < timeoutMs) {
            val binderState = try {
                val s = connection.service?.state
                if (s != null && s >= 0 && s < BaseService.State.values().size) {
                    BaseService.State.values()[s]
                } else null
            } catch (_: Exception) {
                null
            }

            val dsState = DataStore.serviceState
            val cbState = callbackState

            if (binderState == expected) {
                lastState = binderState
                lastSource = "ISagerNetService.getState()"
                val elapsed = System.currentTimeMillis() - start
                return StateObservation(stage, expected, lastState, lastSource, elapsed)
            } else if (cbState == expected) {
                lastState = cbState
                lastSource = "SagerConnection.Callback.stateChanged"
                val elapsed = System.currentTimeMillis() - start
                return StateObservation(stage, expected, lastState, lastSource, elapsed)
            } else if (dsState == expected) {
                lastState = dsState
                lastSource = "DataStore.serviceState"
                val elapsed = System.currentTimeMillis() - start
                return StateObservation(stage, expected, lastState, lastSource, elapsed)
            }

            lastState = binderState ?: cbState
            lastSource = if (binderState != null) "ISagerNetService.getState()" else "SagerConnection.Callback"
            delay(100)
        }

        val elapsed = System.currentTimeMillis() - start
        val failMsg = "$stage FAILED: expected=$expected actual=$lastState source=$lastSource elapsed=${elapsed}ms"
        println(failMsg)
        fail(failMsg)
        throw AssertionError(failMsg)
    }

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        ssHost = args.getString("ss_host")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("ss_host")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("VIALEN_TEST_SS_HOST")?.trim()?.takeIf { it.isNotBlank() }
            ?: ssHost

        runBlocking {
            val app = ApplicationProvider.getApplicationContext<SagerNet>()
            SagerNet.application = app

            val ssBean1 = ShadowsocksBean().applyDefaultValues().apply {
                name = "PhaseB-Deterministic-SS1"
                serverAddress = ssHost
                serverPort = 8388
                method = "chacha20-ietf-poly1305"
                password = "phase-b-test-password"
            }
            profile1 = ProxyEntity().apply {
                id = 9901L
                groupId = 0L
                type = 2 // TYPE_SS
                putBean(ssBean1)
            }

            val ssBean2 = ShadowsocksBean().applyDefaultValues().apply {
                name = "PhaseB-Deterministic-SS2"
                serverAddress = ssHost
                serverPort = 8390
                method = "chacha20-ietf-poly1305"
                password = "phase-b-test-password-2"
            }
            profile2 = ProxyEntity().apply {
                id = 9902L
                groupId = 0L
                type = 2 // TYPE_SS
                putBean(ssBean2)
            }

            runOnDefaultDispatcher {
                SagerDatabase.proxyDao.deleteById(profile1.id)
                SagerDatabase.proxyDao.deleteById(profile2.id)
                SagerDatabase.proxyDao.addProxy(profile1)
                SagerDatabase.proxyDao.addProxy(profile2)
            }

            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"
            DataStore.remoteDns = "local"

            connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            connection.connect(app, callback)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try {
                SagerNet.stopService()
            } catch (_: Exception) {}
            try {
                val app = ApplicationProvider.getApplicationContext<SagerNet>()
                connection.disconnect(app)
            } catch (_: Exception) {}
            runOnDefaultDispatcher {
                SagerDatabase.proxyDao.deleteById(profile1.id)
                SagerDatabase.proxyDao.deleteById(profile2.id)
            }
        }
    }

    private fun testHttpTraffic(maxRetries: Int = 4): String {
        var lastError: Exception? = null
        for (i in 0..maxRetries) {
            try {
                val url = URL("http://$ssHost:8899/fixture")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false

                val code = conn.responseCode
                if (code == 200) {
                    val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText().trim() }
                    conn.disconnect()
                    return response
                }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(250)
            }
        }
        throw lastError ?: RuntimeException("HTTP traffic failed after retries")
    }

    @Test
    fun testRealDeviceFullLifecycle() {
        runBlocking {
            // 1. FIRST CONNECT
            callbackState = BaseService.State.Idle
            DataStore.selectedProxy = profile1.id
            SagerNet.startService()

            val c1 = awaitProductionState(
                stage = "FIRST_CONNECT",
                expected = BaseService.State.Connected,
                timeoutMs = 10_000
            )
            assertEquals(BaseService.State.Connected, c1.actualState)
            println("FIRST_CONNECT: expected=${c1.expected} actual=${c1.actualState} source=${c1.source} elapsed=${c1.elapsedMs}ms PASS")

            // 1b. FIRST TRAFFIC (Independent assertion on SS1 :8388)
            val body1 = testHttpTraffic()
            assertEquals("VIALEN_PHASE_B_OK", body1)
            println("FIRST_TRAFFIC: target=http://$ssHost:8899/fixture inbound=8388 body=$body1 PASS")

            // 2. FIRST STOP
            callbackState = BaseService.State.Stopping
            SagerNet.stopService()
            val s1 = awaitProductionState(
                stage = "FIRST_STOP",
                expected = BaseService.State.Stopped,
                timeoutMs = 10_000
            )
            assertEquals(BaseService.State.Stopped, s1.actualState)
            println("FIRST_STOP: expected=${s1.expected} actual=${s1.actualState} source=${s1.source} elapsed=${s1.elapsedMs}ms PASS")

            // 3. RECONNECT
            callbackState = BaseService.State.Idle
            SagerNet.startService()
            val c2 = awaitProductionState(
                stage = "RECONNECT",
                expected = BaseService.State.Connected,
                timeoutMs = 10_000
            )
            assertEquals(BaseService.State.Connected, c2.actualState)
            println("RECONNECT: expected=${c2.expected} actual=${c2.actualState} source=${c2.source} elapsed=${c2.elapsedMs}ms PASS")

            // 3b. RECONNECT TRAFFIC (Independent assertion on SS1 :8388)
            val body2 = testHttpTraffic()
            assertEquals("VIALEN_PHASE_B_OK", body2)
            println("RECONNECT_TRAFFIC: target=http://$ssHost:8899/fixture inbound=8388 body=$body2 PASS")

            // 4. SECOND STOP
            callbackState = BaseService.State.Stopping
            SagerNet.stopService()
            val s2 = awaitProductionState(
                stage = "SECOND_STOP",
                expected = BaseService.State.Stopped,
                timeoutMs = 10_000
            )
            assertEquals(BaseService.State.Stopped, s2.actualState)
            println("SECOND_STOP: expected=${s2.expected} actual=${s2.actualState} source=${s2.source} elapsed=${s2.elapsedMs}ms PASS")

            // 5. PROFILE SWITCH (Switch to Profile 2 on port 8390)
            callbackState = BaseService.State.Idle
            DataStore.selectedProxy = profile2.id
            SagerNet.startService()
            val c3 = awaitProductionState(
                stage = "PROFILE2_CONNECT",
                expected = BaseService.State.Connected,
                timeoutMs = 10_000
            )
            assertEquals(BaseService.State.Connected, c3.actualState)
            println("PROFILE2_CONNECT: expected=${c3.expected} actual=${c3.actualState} source=${c3.source} elapsed=${c3.elapsedMs}ms PASS")

            // 5b. PROFILE2 TRAFFIC (Independent assertion on SS2 :8390)
            val body3 = testHttpTraffic()
            assertEquals("VIALEN_PHASE_B_OK", body3)
            println("PROFILE2_TRAFFIC: target=http://$ssHost:8899/fixture inbound=8390 body=$body3 PASS")

            // 6. FINAL STOP
            callbackState = BaseService.State.Stopping
            SagerNet.stopService()
            val s3 = awaitProductionState(
                stage = "FINAL_STOP",
                expected = BaseService.State.Stopped,
                timeoutMs = 10_000
            )
            assertEquals(BaseService.State.Stopped, s3.actualState)
            println("FINAL_STOP: expected=${s3.expected} actual=${s3.actualState} source=${s3.source} elapsed=${s3.elapsedMs}ms PASS")
        }
    }
}
