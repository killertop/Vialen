package io.nekohasekai.sagernet.rust

enum class RustProbeStatus {
    SUCCESS,
    INVALID_INPUT,
    INTERNAL_ERROR,
}

data class RustProbeResult(
    val status: RustProbeStatus,
    val contractVersion: Int,
    val inputLength: Int,
    val checksumHex: String?,
)

data class CanonicalProxyResult(
    val status: String,
    val protocol: String = "",
    val server: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val plugin: String = "",
    val name: String = "",
    val error: String? = null,
)

data class CanonicalSubscriptionResult(
    val status: String,
    val lines: List<String> = emptyList(),
    val error: String? = null,
)

object RustBridge {
    const val MAX_INPUT_BYTES: Int = 1024 * 1024

    fun probe(input: ByteArray): RustProbeResult = decodeResponse(RustNative.nativeProbe(input))

    fun parseProxy(uri: String): CanonicalProxyResult = decodeProxyResponse(RustNative.nativeParseProxy(uri))

    fun decodeSubscription(text: String): CanonicalSubscriptionResult = decodeSubscriptionResponse(RustNative.nativeDecodeSubscription(text))

    internal fun decodeLengthPrefixed(buffer: String): List<String> {
        val list = mutableListOf<String>()
        var idx = 0
        while (idx < buffer.length) {
            val colon = buffer.indexOf(':', idx)
            if (colon == -1) break
            val len = buffer.substring(idx, colon).toIntOrNull() ?: break
            val start = colon + 1
            val end = start + len
            if (end > buffer.length) break
            list.add(buffer.substring(start, end))
            idx = end
        }
        return list
    }

    internal fun decodeProxyResponse(response: String): CanonicalProxyResult {
        val newline = response.indexOf('\n')
        if (newline == -1) return CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Empty or malformed response")
        val status = response.substring(0, newline)
        val payload = response.substring(newline + 1)
        val fields = decodeLengthPrefixed(payload)

        if (status != "SUCCESS") {
            return CanonicalProxyResult(
                status = status,
                error = fields.getOrNull(0) ?: "Unknown error"
            )
        }
        if (fields.size < 7) {
            return CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Malformed success payload")
        }
        return CanonicalProxyResult(
            status = "SUCCESS",
            protocol = fields[0],
            server = fields[1],
            port = fields[2].toIntOrNull() ?: 0,
            username = fields[3],
            password = fields[4],
            plugin = fields[5],
            name = fields[6],
        )
    }

    internal fun decodeSubscriptionResponse(response: String): CanonicalSubscriptionResult {
        if (response.startsWith("ERROR|")) {
            return CanonicalSubscriptionResult(
                status = "ERROR",
                error = response.removePrefix("ERROR|")
            )
        }
        val lines = response.lines().filter { it.isNotBlank() }
        return CanonicalSubscriptionResult(
            status = "SUCCESS",
            lines = lines
        )
    }

    internal fun decodeResponse(response: ByteArray): RustProbeResult {
        val fields = response.toString(Charsets.UTF_8).split('|')
        if (fields.size != 4) return internalError()

        val status = runCatching { RustProbeStatus.valueOf(fields[0]) }.getOrNull()
            ?: return internalError()
        val version = fields[1].toIntOrNull() ?: return internalError()
        val inputLength = fields[2].toIntOrNull() ?: return internalError()
        val checksum = fields[3].takeUnless { it == "-" }

        if (version != 1 || inputLength < 0) return internalError()
        if (status == RustProbeStatus.SUCCESS && checksum?.matches(Regex("[0-9a-f]{16}")) != true) {
            return internalError()
        }

        return RustProbeResult(status, version, inputLength, checksum)
    }

    private fun internalError() = RustProbeResult(
        status = RustProbeStatus.INTERNAL_ERROR,
        contractVersion = 1,
        inputLength = 0,
        checksumHex = null,
    )
}

internal object RustNative {
    init {
        System.loadLibrary("vialen_core")
    }

    @JvmStatic
    external fun nativeProbe(input: ByteArray): ByteArray

    @JvmStatic
    external fun nativeParseProxy(uri: String): String

    @JvmStatic
    external fun nativeDecodeSubscription(text: String): String
}
