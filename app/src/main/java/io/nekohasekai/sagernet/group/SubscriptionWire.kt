package io.nekohasekai.sagernet.group

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.internal.LazilyParsedNumber
import java.util.IdentityHashMap

/** Versioned subscription boundary; Universal Kryo payloads remain raw bytes. */
internal object SubscriptionWire {
    private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    class Document(val root: JsonObject, val binaryFields: Map<JsonObject, ByteArray>)

    fun rawDocument(bytes: ByteArray): Document {
        val reader = Reader(bytes, true)
        val root = read(reader).asJsonObject
        return Document(root, reader.binaryFields)
    }

    fun decode(bytes: ByteArray): JsonElement {
        return read(Reader(bytes))
    }

    private fun read(reader: Reader): JsonElement {
        val bytes = reader.bytes
        require(bytes.size >= 4 && bytes[0] == 86.toByte() && bytes[1] == 67.toByte() &&
            bytes[2] == 87.toByte() && bytes[3] == 49.toByte()) { "Invalid subscription wire header" }
        val result = reader.value(0)
        require(reader.position == bytes.size) { "Trailing subscription wire data" }
        return result
    }

    private class Reader(val bytes: ByteArray, val keepBinary: Boolean = false) {
        val binaryFields = IdentityHashMap<JsonObject, ByteArray>()
        var position = 4
        fun byte(): Int {
            require(position < bytes.size) { "Truncated subscription wire" }
            return bytes[position++].toInt() and 255
        }
        fun length(): Int {
            val value = byte().toLong() or (byte().toLong() shl 8) or
                (byte().toLong() shl 16) or (byte().toLong() shl 24)
            require(value <= bytes.size - position) { "Invalid subscription wire length" }
            return value.toInt()
        }
        fun string(): String {
            val size = length()
            val end = position + size
            var cursor = position
            while (cursor < end) {
                val first = bytes[cursor++].toInt() and 255
                if (first < 128) continue
                val remaining = when (first) { in 0xC2..0xDF -> 1; in 0xE0..0xEF -> 2; in 0xF0..0xF4 -> 3; else -> -1 }
                require(remaining >= 0 && cursor + remaining <= end) { "Invalid subscription UTF-8" }
                val second = bytes[cursor].toInt() and 255
                require((first != 0xE0 || second >= 0xA0) && (first != 0xED || second < 0xA0) &&
                    (first != 0xF0 || second >= 0x90) && (first != 0xF4 || second < 0x90)) { "Invalid subscription UTF-8" }
                repeat(remaining) { require((bytes[cursor++].toInt() and 0xC0) == 0x80) { "Invalid subscription UTF-8" } }
            }
            return String(bytes, position, size, Charsets.UTF_8).also { position = end }
        }
        fun value(depth: Int): JsonElement {
            require(depth <= 128) { "Excessive subscription wire depth" }
            return when (byte()) {
                0 -> JsonNull.INSTANCE
                1 -> JsonPrimitive(false)
                2 -> JsonPrimitive(true)
                // Rust emits validated serde_json::Number tokens. Avoid allocating a
                // complete JSON reader for every scalar in this codec.
                3 -> string().let { require(number.matches(it)); JsonPrimitive(LazilyParsedNumber(it)) }
                4 -> JsonPrimitive(string())
                5 -> JsonArray().apply { repeat(length()) { add(value(depth + 1)) } }
                6 -> JsonObject().apply {
                    repeat(length()) {
                        val key = string()
                        require(!has(key)) { "Duplicate subscription wire key" }
                        if (keepBinary && key == "data" && position < bytes.size && bytes[position] == 7.toByte()) {
                            byte()
                            val size = length()
                            binaryFields[this] = bytes.copyOfRange(position, position + size)
                            position += size
                            add(key, JsonNull.INSTANCE)
                        } else add(key, value(depth + 1))
                    }
                }
                7 -> JsonArray().apply { repeat(length()) { add(byte()) } }
                else -> error("Unknown subscription wire tag")
            }
        }
    }
}
