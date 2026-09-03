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

object RustBridge {
    const val MAX_INPUT_BYTES: Int = 1024 * 1024

    fun probe(input: ByteArray): RustProbeResult = decodeResponse(RustNative.nativeProbe(input))

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
}
