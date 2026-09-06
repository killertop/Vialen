package io.nekohasekai.sagernet

import android.content.Intent
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onDefaultDispatcher
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RealDeviceVpnLifecycleTest {
    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()


    private lateinit var profile1: ProxyEntity
    private lateinit var profile2: ProxyEntity
    private lateinit var connection: SagerConnection
    private var ssHost = ""
    private var fixtureGroupId = 0L
    private val runNonce = UUID.randomUUID().toString()
    private var targetRuleId = 0L
    private var savedSettings = emptyMap<String, KeyValuePair?>()

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

            if (binderState == expected) {
                val elapsed = System.currentTimeMillis() - start
                return StateObservation(stage, expected, binderState, "ISagerNetService.getState()", elapsed)
            }
            lastState = binderState ?: BaseService.State.Idle
            lastSource = "binder=$binderState callback=$callbackState datastore=${DataStore.serviceState}"
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
            // Fail before owning a connection or changing fixture/settings state.
            assertTrue("Supply ss_host for the controlled dual-SS/HTTP fixture", ssHost.isNotBlank())
            assertNull("Grant VPN consent before the positive lifecycle test", VpnService.prepare(app))
            check(!DataStore.serviceState.started) { "Refusing to interrupt an existing VPN" }

            val settingKeys = listOf(Key.SERVICE_MODE, Key.DIRECT_DNS, Key.REMOTE_DNS,
                Key.BYPASS_LAN, Key.BYPASS_LAN_IN_CORE, Key.PROXY_APPS, Key.ENABLE_FAKEDNS, Key.APPEND_HTTP_PROXY)
            savedSettings = settingKeys.associateWith { key -> PublicDatabase.kvPairDao[key] }

            val ssBean1 = ShadowsocksBean().applyDefaultValues().apply {
                name = "PhaseB-Deterministic-SS1"
                serverAddress = ssHost
                serverPort = 8388
                method = "chacha20-ietf-poly1305"
                password = "phase-b-test-password"
            }
            profile1 = ProxyEntity().apply {
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
                type = 2 // TYPE_SS
                putBean(ssBean2)
            }

            onDefaultDispatcher {
                SagerDatabase.instance.runInTransaction {
                    fixtureGroupId = SagerDatabase.instance.groupDao().createGroup(
                        ProxyGroup(name = "lifecycle-$runNonce"))
                    profile1.groupId = fixtureGroupId
                    profile2.groupId = fixtureGroupId
                    profile1.id = SagerDatabase.proxyDao.addProxy(profile1)
                    profile2.id = SagerDatabase.proxyDao.addProxy(profile2)
                    targetRuleId = SagerDatabase.rulesDao.createRule(RuleEntity(
                        name = "ss-route-$runNonce", userOrder = Long.MIN_VALUE, enabled = true,
                        ip = "198.18.0.254/32", outbound = 0))
                }
            }

            DataStore.serviceMode = Key.MODE_VPN
            DataStore.directDns = "local"
            DataStore.remoteDns = "local"
            DataStore.bypassLan = false
            DataStore.bypassLanInCore = false
            DataStore.proxyApps = false
            DataStore.enableFakeDns = false
            DataStore.appendHttpProxy = false

            app.startActivity(app.packageManager.getLaunchIntentForPackage(app.packageName)!!
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
            connection.connect(app, callback)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            profileState.cleanupSteps({
                if (::connection.isInitialized) profileState.stopAndAwait(connection)
            }, {
                if (::connection.isInitialized) connection.disconnect(ApplicationProvider.getApplicationContext<SagerNet>())
            }, {
                onDefaultDispatcher {
                    SagerDatabase.instance.runInTransaction {
                        if (::profile1.isInitialized && profile1.id != 0L) SagerDatabase.proxyDao.deleteById(profile1.id)
                        if (::profile2.isInitialized && profile2.id != 0L) SagerDatabase.proxyDao.deleteById(profile2.id)
                        if (targetRuleId != 0L) SagerDatabase.rulesDao.deleteById(targetRuleId)
                        if (fixtureGroupId != 0L) SagerDatabase.instance.groupDao().deleteById(fixtureGroupId)
                    }
                    if (::profile1.isInitialized && profile1.id != 0L) check(SagerDatabase.proxyDao.getById(profile1.id) == null)
                    if (::profile2.isInitialized && profile2.id != 0L) check(SagerDatabase.proxyDao.getById(profile2.id) == null)
                    if (targetRuleId != 0L) check(SagerDatabase.rulesDao.getById(targetRuleId) == null)
                    if (fixtureGroupId != 0L) check(SagerDatabase.instance.groupDao().getById(fixtureGroupId) == null)
                }
                println("SS_ROUTE_CLEANUP temporary_profiles_and_rule_absent=true owned_group_absent=true")
            }, {
                PublicDatabase.instance.runInTransaction {
                    savedSettings.forEach { (key, row) ->
                        if (row == null) PublicDatabase.kvPairDao.delete(key) else PublicDatabase.kvPairDao.put(row)
                    }
                }
                savedSettings.forEach { (key, expected) ->
                    val actual = PublicDatabase.kvPairDao[key]
                    check(if (expected == null) actual == null else actual != null &&
                        actual.valueType == expected.valueType && actual.value.contentEquals(expected.value)) {
                        "Lifecycle setting not restored: $key"
                    }
                }
                println("SS_ROUTE_CLEANUP changed_settings_restored=true")
            })
        }
    }

    private fun testHttpTraffic(stage: String): JSONObject {
        val nonce = "$runNonce-$stage"
        val conn = URL("http://198.18.0.254/probe?nonce=$nonce&stage=$stage")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Cache-Control", "no-store")
            conn.setRequestProperty("Connection", "close")
            assertEquals("Probe HTTP status", 200, conn.responseCode)
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun assertProbe(response: JSONObject, stage: String, expectedIdentity: String, expectedPort: Int) {
        assertEquals("Probe nonce", "$runNonce-$stage", response.getString("nonce"))
        assertEquals("Probe stage", stage, response.getString("stage"))
        assertEquals("Observed ingress identity", expectedIdentity, response.getString("identity"))
        assertEquals("Observed ingress port", expectedPort, response.getInt("port"))
        val counts = response.getJSONObject("counts")
        assertEquals("SS1 nonce count", if (expectedIdentity == "ss1") 1 else 0, counts.getInt("ss1"))
        assertEquals("SS2 nonce count", if (expectedIdentity == "ss2") 1 else 0, counts.getInt("ss2"))
        println("SS_ROUTE_PROBE stage=$stage nonce=${response.getString("nonce")} identity=${response.getString("identity")} port=${response.getInt("port")} ss1=${counts.getInt("ss1")} ss2=${counts.getInt("ss2")} PASS")
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
            val body1 = testHttpTraffic("first")
            assertThrows(AssertionError::class.java) { assertProbe(body1, "first", "ss2", 8390) }
            println("SS_ROUTE_NEGATIVE_ORACLE actual=ss1 expected=ss2 rejected=true")
            assertProbe(body1, "first", "ss1", 8388)

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
            val body2 = testHttpTraffic("reconnect")
            assertProbe(body2, "reconnect", "ss1", 8388)

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
            val body3 = testHttpTraffic("switch")
            assertProbe(body3, "switch", "ss2", 8390)

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
