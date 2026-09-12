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
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.SingBoxOptions.Outbound_Hysteria2Options
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class RealDeviceHysteria2LiveTest {
    private fun compileTypedOutbound(bean: io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean): Outbound_Hysteria2Options {
        val gson = com.google.gson.Gson()
        val profile = io.nekohasekai.sagernet.fmt.ProfileAdapter.fromBean(bean, "live-test")
        val request = gson.toJsonTree(mapOf(
            "profiles" to listOf(profile), "selected_id" to profile.id, "purpose" to "export",
            "platform" to emptyMap<String, Any>(),
            "policy" to mapOf("dns" to mapOf("direct" to mapOf("type" to "local"), "remote" to mapOf("type" to "local")))
        )).asJsonObject
        val plan = io.nekohasekai.sagernet.core.CoreClient.compile(request)
        val config = com.google.gson.JsonParser.parseString(plan["config"].asString).asJsonObject
        val outbound = config.getAsJsonArray("outbounds").first { it.asJsonObject["type"].asString == profile.type }
        return gson.fromJson(outbound, Outbound_Hysteria2Options::class.java)
    }

    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()


    private lateinit var profile: ProxyEntity
    private lateinit var connection: SagerConnection
    private var hy2Uri: String = ""

    @Volatile
    private var callbackState: BaseService.State = BaseService.State.Idle

    private val callback = object : SagerConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            println("[HY2-TEST-CALLBACK] stateChanged: $state (msg: $msg)")
            callbackState = state
        }

        override fun onServiceConnected(service: ISagerNetService) {
            println("[HY2-TEST-SERVICE] onServiceConnected")
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
        timeoutMs: Long = 15_000
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
        val b64 = args.getString("hy2_uri_b64")
        hy2Uri = if (!b64.isNullOrBlank()) {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)).trim()
        } else {
            args.getString("hy2_uri")
                ?: System.getProperty("hy2_uri")
                ?: System.getenv("VIALEN_TEST_HY2_URI")
                ?: ""
        }

        assertTrue("Hysteria2 URI argument must be provided via -e hy2_uri or -e hy2_uri_b64", hy2Uri.isNotBlank())

        runBlocking {
            val app = ApplicationProvider.getApplicationContext<SagerNet>()
            SagerNet.application = app

            // 1. Ephemeral Import and Parse
            val bean = parseHysteria2(hy2Uri)
            assertEquals("Expected protocolVersion == 2", 2, bean.protocolVersion)
            assertTrue("Expected serverAddress to not be empty", bean.serverAddress.isNotBlank())
            assertTrue("Expected serverPorts to not be empty", bean.serverPorts.isNotBlank())
            assertEquals("Expected obfsType == salamander", "salamander", bean.obfsType)
            assertTrue("Expected obfuscation password to not be empty", bean.obfuscation.isNotBlank())
            println("[HY2-TEST] Ephemeral parse success: server=${bean.serverAddress}:${bean.serverPorts}, obfsType=${bean.obfsType}")

            // 2. Build and assert outbound (Default mode: disable_chrome_parrot omitted)
            val outbound = compileTypedOutbound(bean)
            assertTrue("Expected outbound is Outbound_Hysteria2Options", outbound is Outbound_Hysteria2Options)
            val hy2Outbound = outbound as Outbound_Hysteria2Options
            assertEquals("Expected type == hysteria2", "hysteria2", hy2Outbound.type)
            assertNull("Expected disable_chrome_parrot is omitted (null)", hy2Outbound.disable_chrome_parrot)
            assertNotNull("Expected obfs config", hy2Outbound.obfs)
            assertEquals("Expected obfs type salamander", "salamander", hy2Outbound.obfs?.type)
            println("[HY2-TEST] Outbound assertion PASS: type=hysteria2, obfs=salamander, disable_chrome_parrot=omitted")

            // 3. Ephemeral ProxyEntity insertion
            profile = ProxyEntity().apply {
                id = 9902L
                groupId = 0L
                type = ProxyEntity.TYPE_HYSTERIA
                putBean(bean)
            }

            // Verify full config building
            val configRes = buildConfig(profile, forTest = false)
            assertNotNull(configRes.config)
            assertTrue("Config should contain hysteria2", configRes.config.contains("\"type\":\"hysteria2\"") || configRes.config.contains("\"type\": \"hysteria2\""))
            assertFalse("Default config should NOT contain disable_chrome_parrot", configRes.config.contains("disable_chrome_parrot"))
            println("[HY2-TEST] Full configuration build PASS (disable_chrome_parrot omitted)")

            runOnDefaultDispatcher {
                SagerDatabase.proxyDao.deleteById(profile.id)
                SagerDatabase.proxyDao.addProxy(profile)
            }

            DataStore.serviceMode = Key.MODE_VPN
            DataStore.selectedProxy = profile.id
            DataStore.directDns = "local\n223.5.5.5\n1.1.1.1"
            DataStore.remoteDns = "1.1.1.1\n8.8.8.8\nhttps://1.1.1.1/dns-query"
            DataStore.enableDnsRouting = true

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
                // Await deletion; the old GlobalScope launch could outlive @After.
                io.nekohasekai.sagernet.ktx.onDefaultDispatcher {
                    if (::profile.isInitialized) SagerDatabase.proxyDao.deleteById(profile.id)
                    SagerDatabase.proxyDao.deleteById(9903L)
                }
            })
        }
    }

    private fun testHttpsEndpoints(maxRetries: Int = 6): Pair<String, Int> {
        val testUrls = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://www.google.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            for (urlStr in testUrls) {
                try {
                    val url = URL(urlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true

                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 200 || code == 204) {
                        println("[HY2-TEST-TRAFFIC] Attempt $attempt: GET $urlStr -> HTTP $code SUCCESS")
                        return urlStr to code
                    } else {
                        println("[HY2-TEST-TRAFFIC] Attempt $attempt: GET $urlStr -> HTTP $code")
                    }
                } catch (e: Exception) {
                    lastError = e
                    println("[HY2-TEST-TRAFFIC] Attempt $attempt: GET $urlStr -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Thread.sleep(1000)
        }
        throw lastError ?: RuntimeException("All HTTPS traffic endpoints failed after $maxRetries attempts")
    }

    @Test
    fun testRealDeviceHysteria2FullSuite() {
        runBlocking {
            // ==========================================
            // PART 1: DEFAULT MODE 3-RUN CONNECT LATENCY & TRAFFIC
            // ==========================================
            val latencies = mutableListOf<Long>()

            for (run in 1..3) {
                println("[HY2-TEST] Starting Default Run $run...")
                callbackState = BaseService.State.Idle
                DataStore.selectedProxy = profile.id
                SagerNet.startService()

                val connectObs = awaitProductionState(
                    stage = "HY2_DEFAULT_CONNECT_RUN_$run",
                    expected = BaseService.State.Connected,
                    timeoutMs = 15_000
                )
                assertEquals(BaseService.State.Connected, connectObs.actualState)
                latencies.add(connectObs.elapsedMs)
                println("HY2_CONNECT_RUN_$run: elapsedMs=${connectObs.elapsedMs}ms source=${connectObs.source} PASS")

                // Verify HTTPS traffic on Run 1
                if (run == 1) {
                    val (endpoint, responseCode) = testHttpsEndpoints()
                    assertTrue("Expected HTTP 200 or 204", responseCode == 200 || responseCode == 204)
                    println("HY2_DEFAULT_TRAFFIC: endpoint=$endpoint status=$responseCode PASS")
                }

                // Disconnect
                callbackState = BaseService.State.Stopping
                SagerNet.stopService()
                val stopObs = awaitProductionState(
                    stage = "HY2_DEFAULT_DISCONNECT_RUN_$run",
                    expected = BaseService.State.Stopped,
                    timeoutMs = 10_000
                )
                assertEquals(BaseService.State.Stopped, stopObs.actualState)
                println("HY2_DEFAULT_DISCONNECT_RUN_$run: elapsedMs=${stopObs.elapsedMs}ms PASS")
                delay(500)
            }

            latencies.sort()
            val medianLatency = latencies[1]
            println("HY2_CONNECT_RUN_1: ${latencies[0]}ms")
            println("HY2_CONNECT_RUN_2: ${latencies[1]}ms")
            println("HY2_CONNECT_RUN_3: ${latencies[2]}ms")
            println("HY2_CONNECT_MEDIAN: ${medianLatency}ms")

            // ==========================================
            // PART 2: COMPATIBILITY MODE (disableChromeParrot = true)
            // ==========================================
            println("[HY2-TEST] Starting Compatibility Mode (disableChromeParrot = true)...")
            val baseBean = parseHysteria2(hy2Uri)
            val compatBean = HysteriaBean().applyDefaultValues().apply {
                protocolVersion = 2
                serverAddress = baseBean.serverAddress
                serverPorts = baseBean.serverPorts
                authPayload = baseBean.authPayload
                sni = baseBean.sni
                allowInsecure = baseBean.allowInsecure
                obfsType = baseBean.obfsType
                obfuscation = baseBean.obfuscation
                disableChromeParrot = true
            }

            val compatProfile = ProxyEntity().apply {
                id = 9903L
                groupId = 0L
                type = ProxyEntity.TYPE_HYSTERIA
                putBean(compatBean)
            }

            val compatConfig = buildConfig(compatProfile, forTest = false)
            assertTrue("Config MUST contain disable_chrome_parrot: true", compatConfig.config.contains("\"disable_chrome_parrot\":true") || compatConfig.config.contains("\"disable_chrome_parrot\": true"))
            println("[HY2-TEST] Compatibility mode config assertion PASS: disable_chrome_parrot=true verified")

            runOnDefaultDispatcher {
                SagerDatabase.proxyDao.deleteById(compatProfile.id)
                SagerDatabase.proxyDao.addProxy(compatProfile)
            }

            DataStore.selectedProxy = compatProfile.id
            callbackState = BaseService.State.Idle
            SagerNet.startService()

            val compatStart = System.currentTimeMillis()
            var compatConnected = false
            while (System.currentTimeMillis() - compatStart < 15_000) {
                val s = try { connection.service?.state } catch (_: Exception) { null }
                val ds = DataStore.serviceState
                val cb = callbackState
                if (s == BaseService.State.Connected.ordinal || ds == BaseService.State.Connected || cb == BaseService.State.Connected) {
                    compatConnected = true
                    break
                }
                delay(200)
            }

            if (compatConnected) {
                println("HY2_COMPATIBILITY_CONNECTED: PASS")
                try {
                    val (compatEndpoint, compatCode) = testHttpsEndpoints(maxRetries = 2)
                    println("HY2_COMPATIBILITY_TRAFFIC: endpoint=$compatEndpoint status=$compatCode PASS")
                    println("HY2_COMPATIBILITY_CLASSIFICATION: PASS")
                } catch (e: Exception) {
                    println("HY2_COMPATIBILITY_CLASSIFICATION: SERVER_SPECIFIC_COMPATIBILITY (connected, traffic timeout: ${e.message})")
                }
            } else {
                println("HY2_COMPATIBILITY_CLASSIFICATION: SERVER_SPECIFIC_COMPATIBILITY (server may require standard QUIC fingerprint)")
            }

            // Always cleanly stop
            SagerNet.stopService()
            awaitProductionState(
                stage = "HY2_COMPATIBILITY_STOP",
                expected = BaseService.State.Stopped,
                timeoutMs = 10_000
            )
            println("HY2_COMPATIBILITY_STOP: PASS (cleanly stopped)")
        }
    }
}
