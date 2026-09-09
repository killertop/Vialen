package io.nekohasekai.sagernet

import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import org.junit.Assert.*
import org.junit.Test

class SingBoxOptionAdapterTest {
    class NestedOption : SingBoxOption() {
        var label: String = "base"
        var child: SingBoxOption? = null
    }

    @Test fun registeredDelegatePreservesNestedOptionsAndMergePrecedence() {
        val child = NestedOption().apply {
            _hack_config_map["from_map"] = true
            _hack_custom_config = """{"label":"child override"}"""
        }
        val parent = NestedOption().apply {
            this.child = child
            _hack_config_map["value"] = 2L
            _hack_custom_config = """{"value":3,"extra":{"enabled":true}}"""
        }
        val result = parent.asMap()
        assertEquals("base", result["label"])
        assertEquals(3L, result["value"])
        assertEquals(mapOf("enabled" to true), result["extra"])
        assertEquals(mapOf("label" to "child override", "from_map" to true), result["child"])
        assertFalse(result.keys.any { it.startsWith("_hack") })
    }

    @Test fun customOptionRetainsNestedJsonValuesAndMergeHooks() {
        val option = CustomSingBoxOption("""{"items":[true,2,{"key":"value"}],"object":{"old":1}}""")
        option._hack_config_map["object"] = mapOf("added" to false)
        val result = option.asMap()
        assertEquals(listOf(true, 2L, mapOf("key" to "value")), result["items"])
        assertEquals(mapOf("old" to 1L, "added" to false), result["object"])
    }
}
