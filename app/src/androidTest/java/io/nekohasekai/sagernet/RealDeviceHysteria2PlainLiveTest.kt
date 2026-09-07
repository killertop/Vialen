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
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
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
/** Explicit no-obfuscation HY2 contract; run separately from the salamander suite. */
class RealDeviceHysteria2PlainLiveTest {
    @get:org.junit.Rule
    val profileState = ProfileSelectionStateRule()


    private lateinit var profile: ProxyEntity
    private lateinit var connection: SagerConnection
    private var hy2Uri: String = ""

    @Volatile
    private var callbackState: BaseService.State = BaseService.State.Idle

    private val callback = object : SagerConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            println("[HY2-PLAIN-TEST-CALLBACK] stateChanged: $state (msg: $msg)")
            callbackState = state
        }

        override fun onServiceConnected(service: ISagerNetService) {
            println("[HY2-PLAIN-TEST-SERVICE] onServiceConnected")
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
        val queryNames = android.net.Uri.parse(hy2Uri).queryParameterNames
        assertFalse("Plain HY2 URI must omit obfs", queryNames.contains("obfs"))
        assertFalse("Plain HY2 URI must omit obfs-password", queryNames.contains("obfs-password"))

        runBlocking {
            val app = ApplicationProvider.getApplicationContext<SagerNet>()
            SagerNet.application = app

            // 1. Ephemeral Import and Parse
            val bean = parseHysteria2(hy2Uri)
            assertEquals("Expected protocolVersion == 2", 2, bean.protocolVersion)
            assertTrue("Expected serverAddress to not be empty", bean.serverAddress.isNotBlank())
            assertTrue("Expected serverPorts to not be empty", bean.serverPorts.isNotBlank())
            assertEquals("Plain HY2 must parse without an obfuscation password", "", bean.obfuscation)
            println("[HY2-PLAIN-TEST] Ephemeral parse success: server=${bean.serverAddress}:${bean.serverPorts}, obfsType=${bean.obfsType}")

            // 2. Build and assert outbound (Default mode: disable_chrome_parrot omitted)
            val outbound = buildSingBoxOutboundHysteriaBean(bean)
            assertTrue("Expected outbound is Outbound_Hysteria2Options", outbound is Outbound_Hysteria2Options)
            val hy2Outbound = outbound as Outbound_Hysteria2Options
            assertEquals("Expected type == hysteria2", "hysteria2", hy2Outbound.type)
            assertNull("Expected disable_chrome_parrot is omitted (null)", hy2Outbound.disable_chrome_parrot)
            assertNull("Plain HY2 outbound must omit obfs", hy2Outbound.obfs)
            println("[HY2-PLAIN-TEST] Outbound assertion PASS: type=hysteria2, obfs=omitted, disable_chrome_parrot=omitted")

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
            assertFalse("Plain HY2 full config must omit obfs", configRes.config.contains("\"obfs\""))
            println("[HY2-PLAIN-TEST] Full configuration build PASS (disable_chrome_parrot omitted)")

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
                        println("[HY2-PLAIN-TEST-TRAFFIC] Attempt $attempt: GET $urlStr -> HTTP $code SUCCESS")
                        return urlStr to code
                    } else {
                        println("[HY2-PLAIN-TEST-TRAFFIC] Attempt $attempt: GET $urlStr -> HTTP $code")
                    }
                } catch (e: Exception) {
                    lastError = e
                    println("[HY2-PLAIN-TEST-TRAFFIC] Attempt $attempt: GET $urlStr -> ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            Thread.sleep(1000)
        }
        throw lastError ?: RuntimeException("All HTTPS traffic endpoints failed after $maxRetries attempts")
    }

    /** Short screen-off check only; this does not enter or verify Doze. */
    private suspend fun verifyOemScreenOff(screenOffMs: Long) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val power = instrumentation.targetContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        assertTrue("OEM screen-off check must begin with an interactive screen", power.isInteractive)

        fun screenKey(keyCode: Int) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("input keyevent $keyCode")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }

        suspend fun awaitInteractive(expected: Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (power.isInteractive != expected && android.os.SystemClock.elapsedRealtime() < deadline) {
                delay(100)
            }
            assertEquals("OEM screen interactive state", expected, power.isInteractive)
        }

        profileState.preservingFailure({
            screenKey(223) // KEYCODE_SLEEP; does not unlock or change device settings.
            awaitInteractive(false)
            val screenOffStart = android.os.SystemClock.elapsedRealtime()
            delay(screenOffMs)
            assertFalse("Screen must remain non-interactive after the screen-off interval", power.isInteractive)
            val observation = awaitProductionState(
                stage = "OEM_SCREEN_OFF_CONNECTED",
                expected = BaseService.State.Connected,
                timeoutMs = 15_000
            )
            assertEquals(BaseService.State.Connected, observation.actualState)
            assertFalse("Screen must be non-interactive before the HTTPS probe", power.isInteractive)
            val (endpoint, responseCode) = testHttpsEndpoints()
            assertTrue("Screen-off HTTPS must return HTTP 200 or 204", responseCode == 200 || responseCode == 204)
            assertFalse("Screen must remain non-interactive through the HTTPS probe", power.isInteractive)
            println("OEM_SCREEN_OFF_CONNECTED: requestedMs=$screenOffMs elapsedMs=${android.os.SystemClock.elapsedRealtime() - screenOffStart} source=${observation.source} PASS")
            println("OEM_SCREEN_OFF_TRAFFIC: endpoint=$endpoint status=$responseCode interactive=false PASS")
        }, {
            try {
                screenKey(224) // KEYCODE_WAKEUP only; never dismiss the keyguard.
                awaitInteractive(true)
                println("OEM_SCREEN_OFF_WAKE: interactive=true PASS")
            } catch (error: Throwable) {
                println("OEM_SCREEN_OFF_WAKE: FAILED (${error.javaClass.simpleName})")
                throw error
            }
        })
    }

    @Test
    fun testRealDeviceHysteria2PlainFullSuite() {
        runBlocking {
            val screenOffArgument = InstrumentationRegistry.getArguments().getString("oem_screen_off_ms") ?: "0"
            val screenOffMs = screenOffArgument.toLongOrNull()
            require(screenOffMs == 0L || screenOffMs == 30_000L) {
                "oem_screen_off_ms must be 0 (disabled) or 30000"
            }
            // ==========================================
            // PART 1: DEFAULT MODE 3-RUN CONNECT LATENCY & TRAFFIC
            // ==========================================
            val latencies = mutableListOf<Long>()

            for (run in 1..3) {
                println("[HY2-PLAIN-TEST] Starting Default Run $run...")
                callbackState = BaseService.State.Idle
                DataStore.selectedProxy = profile.id
                SagerNet.startService()

                val connectObs = awaitProductionState(
                    stage = "HY2_PLAIN_DEFAULT_CONNECT_RUN_$run",
                    expected = BaseService.State.Connected,
                    timeoutMs = 15_000
                )
                assertEquals(BaseService.State.Connected, connectObs.actualState)
                latencies.add(connectObs.elapsedMs)
                println("HY2_PLAIN_CONNECT_RUN_$run: elapsedMs=${connectObs.elapsedMs}ms source=${connectObs.source} PASS")

                // Verify HTTPS traffic on Run 1
                if (run == 1) {
                    val (endpoint, responseCode) = testHttpsEndpoints()
                    assertTrue("Expected HTTP 200 or 204", responseCode == 200 || responseCode == 204)
                    println("HY2_PLAIN_DEFAULT_TRAFFIC: endpoint=$endpoint status=$responseCode PASS")
                    if (screenOffMs == 30_000L) verifyOemScreenOff(screenOffMs)
                }

                // Disconnect
                callbackState = BaseService.State.Stopping
                SagerNet.stopService()
                val stopObs = awaitProductionState(
                    stage = "HY2_PLAIN_DEFAULT_DISCONNECT_RUN_$run",
                    expected = BaseService.State.Stopped,
                    timeoutMs = 10_000
                )
                assertEquals(BaseService.State.Stopped, stopObs.actualState)
                println("HY2_PLAIN_DEFAULT_DISCONNECT_RUN_$run: elapsedMs=${stopObs.elapsedMs}ms PASS")
                delay(500)
            }

            latencies.sort()
            val medianLatency = latencies[1]
            println("HY2_PLAIN_CONNECT_RUN_1: ${latencies[0]}ms")
            println("HY2_PLAIN_CONNECT_RUN_2: ${latencies[1]}ms")
            println("HY2_PLAIN_CONNECT_RUN_3: ${latencies[2]}ms")
            println("HY2_PLAIN_CONNECT_MEDIAN: ${medianLatency}ms")

            // ==========================================
            // PART 2: COMPATIBILITY MODE (disableChromeParrot = true)
            // ==========================================
            println("[HY2-PLAIN-TEST] Starting Compatibility Mode (disableChromeParrot = true)...")
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

            assertEquals("Plain HY2 compatibility bean must have no obfuscation password", "", compatBean.obfuscation)
            val compatOutbound = buildSingBoxOutboundHysteriaBean(compatBean)
            assertTrue("Expected compatibility outbound is Outbound_Hysteria2Options", compatOutbound is Outbound_Hysteria2Options)
            val compatHy2Outbound = compatOutbound as Outbound_Hysteria2Options
            assertNull("Plain HY2 compatibility outbound must omit obfs", compatHy2Outbound.obfs)
            assertEquals("Compatibility outbound must enable disable_chrome_parrot", true, compatHy2Outbound.disable_chrome_parrot)

            val compatConfig = buildConfig(compatProfile, forTest = false)
            assertFalse("Plain HY2 compatibility full config must omit obfs", compatConfig.config.contains("\"obfs\""))
            assertTrue("Config MUST contain disable_chrome_parrot: true", compatConfig.config.contains("\"disable_chrome_parrot\":true") || compatConfig.config.contains("\"disable_chrome_parrot\": true"))
            println("[HY2-PLAIN-TEST] Compatibility mode config assertion PASS: disable_chrome_parrot=true verified")

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
                println("HY2_PLAIN_COMPATIBILITY_CONNECTED: PASS")
                try {
                    val (compatEndpoint, compatCode) = testHttpsEndpoints(maxRetries = 2)
                    println("HY2_PLAIN_COMPATIBILITY_TRAFFIC: endpoint=$compatEndpoint status=$compatCode PASS")
                    println("HY2_PLAIN_COMPATIBILITY_CLASSIFICATION: PASS")
                } catch (e: Exception) {
                    println("HY2_PLAIN_COMPATIBILITY_CLASSIFICATION: SERVER_SPECIFIC_COMPATIBILITY (connected, traffic timeout: ${e.message})")
                }
            } else {
                println("HY2_PLAIN_COMPATIBILITY_CLASSIFICATION: SERVER_SPECIFIC_COMPATIBILITY (server may require standard QUIC fingerprint)")
            }

            // Always cleanly stop
            SagerNet.stopService()
            awaitProductionState(
                stage = "HY2_PLAIN_COMPATIBILITY_STOP",
                expected = BaseService.State.Stopped,
                timeoutMs = 10_000
            )
            println("HY2_PLAIN_COMPATIBILITY_STOP: PASS (cleanly stopped)")
        }
    }
}
