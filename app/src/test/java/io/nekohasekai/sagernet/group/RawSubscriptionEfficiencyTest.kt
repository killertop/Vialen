package io.nekohasekai.sagernet.group

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.RustBridgeRobolectricTestRunner
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RustBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RawSubscriptionEfficiencyTest {
    private fun response(vararg links: String): ByteArray = JsonObject().apply {
        addProperty("version", 1)
        addProperty("status", "SUCCESS")
        add("nodes", com.google.gson.JsonArray().apply {
            links.forEach { link -> add(JsonObject().apply {
                addProperty("kind", "Universal")
                addProperty("initialize", true)
                add("fields", JsonObject().apply { addProperty("link", link) })
            }) }
        })
    }.toString().encodeToByteArray()

    private class Bean : SOCKSBean() {
        var initializations = 0
        override fun initializeDefaultValues() { initializations++ }
    }

    @Test fun successfulDuplicatesDecodeOncePerPositionAndRemainIndependent() {
        val decoded = mutableListOf<Bean>()
        val result = RustRawSubscription.parseWithCodecs(JsonObject(),
            { response("same", "same") },
            { Bean().also(decoded::add) })!!
        assertEquals(2, decoded.size)
        assertSame(decoded[0], result[0])
        assertSame(decoded[1], result[1])
        assertNotSame(result[0], result[1])
        result[0].name = "mutated"
        assertNull(result[1].name)
        assertEquals(listOf(1, 1), decoded.map { it.initializations })
    }

    @Test fun universalCacheTracksResponsePositionsInMixedResults() {
        val encoded = JsonParser.parseString(response("same").decodeToString()).asJsonObject
        encoded["nodes"].asJsonArray.add(JsonParser.parseString(
            """{"kind":"SOCKS","fields":{"name":"plain"},"initialize":false}"""))
        encoded["nodes"].asJsonArray.add(JsonParser.parseString(
            response("same").decodeToString()).asJsonObject["nodes"].asJsonArray[0])
        val decoded = mutableListOf<Bean>()
        val result = RustRawSubscription.parseWithCodecs(JsonObject(),
            { encoded.toString().encodeToByteArray() }, { Bean().also(decoded::add) })!!
        assertEquals(2, decoded.size)
        assertSame(decoded[0], result[0])
        assertEquals("plain", result[1].name)
        assertSame(decoded[1], result[2])
        assertNotSame(result[0], result[2])
    }

    @Test fun failedIterationDiscardsEveryDecodedBeanAndRetriesWithInvalidLinks() {
        val decoded = mutableListOf<Bean>()
        var requests = 0
        var decodeCalls = 0
        val result = RustRawSubscription.parseWithCodecs(JsonObject(), { input ->
            val invalid = JsonParser.parseString(input.decodeToString()).asJsonObject["invalid_universal"].asJsonArray
            if (requests++ == 0) {
                assertEquals(0, invalid.size())
                response("same", "bad", "same")
            } else {
                assertEquals(listOf("bad"), invalid.map { it.asString })
                response("same", "same")
            }
        }, { fields ->
            decodeCalls++
            check(fields["link"].asString != "bad")
            Bean().also(decoded::add)
        })!!
        assertEquals(2, requests)
        assertEquals(5, decodeCalls)
        assertEquals(4, decoded.size)
        assertSame(decoded[2], result[0])
        assertSame(decoded[3], result[1])
        assertEquals(listOf(0, 0, 1, 1), decoded.map { it.initializations })
    }

    @Test fun rejectedLinkRepeatedByCodecStillFailsInsteadOfReusingStaleState() {
        var requests = 0
        try {
            RustRawSubscription.parseWithCodecs(JsonObject(),
                { requests++; response("bad") }, { error("bad payload") })
            fail("Expected stalled retry to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("Invalid universal codec retry", expected.message)
            assertEquals(2, requests)
        }
    }
    private fun projected(fields: String): SOCKSBean {
        val encoded = """{"version":1,"status":"SUCCESS","nodes":[{"kind":"SOCKS","fields":$fields,"initialize":false}]}"""
        return RustRawSubscription.parseWithCodecs(JsonObject(),
            { encoded.encodeToByteArray() }, { error("Unexpected Universal decode") })!!.single() as SOCKSBean
    }

    @Test fun cachedFieldsPreserveInheritedFieldsNullsAndDistinctBeans() {
        val first = projected("""{"name":"name","serverPort":1080,"username":null,"sUoT":true}""")
        val second = projected("""{"name":"other","serverPort":1081,"username":"user","sUoT":false}""")
        assertEquals("name", first.name)
        assertEquals(1080, first.serverPort.toInt())
        assertNull(first.username)
        assertTrue(first.sUoT)
        assertEquals("other", second.name)
        assertEquals(1081, second.serverPort.toInt())
        assertEquals("user", second.username)
        assertFalse(second.sUoT)
        assertNotSame(first, second)
    }

    @Test fun cachedFieldsStillRejectUnknownStaticAndWrongTypedValues() {
        repeat(2) {
            try {
                projected("""{"notAField":1}""")
                fail("Unknown fields must fail")
            } catch (_: NoSuchFieldException) { }
            try {
                projected("""{"PROTOCOL_SOCKS5":1}""")
                fail("Static fields must fail")
            } catch (_: IllegalStateException) { }
            try {
                projected("""{"serverPort":"not a number"}""")
                fail("Wrong field types must fail")
            } catch (_: com.google.gson.JsonSyntaxException) { }
        }
    }

}
