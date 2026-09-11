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
    val sni: String = "",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val disableSNI: Boolean = false,
    val transportType: String = "",
    val transportHost: String = "",
    val transportPath: String = "",
    val congestionControl: String = "",
    val udpRelayMode: String = "",
    val authPayload: String = "",
    val serverPorts: String = "",
    val obfsType: String = "",
    val obfsPassword: String = "",
    val uploadMbps: Int = 0,
    val downloadMbps: Int = 0,
    val certificates: String = "",
    val utlsFingerprint: String = "",
    val realityPubKey: String = "",
    val realityShortId: String = "",
    val earlyDataHeaderName: String = "",
    val wsMaxEarlyData: Int = 0,
    val packetEncoding: Int = 0,
    val disableChromeParrot: Boolean = false,
    val bbrProfile: String = "",
    val hopIntervalMax: Int = 0,
    val obfsMinPacketSize: Int = 512,
    val obfsMaxPacketSize: Int = 1200,
    val tlsEnabled: Boolean = false,
    val serviceName: String = "",
    val alterId: Int = 0,
    val encryption: String = "",
    val error: String? = null,
)

data class CanonicalSubscriptionResult(
    val status: String,
    val lines: List<String> = emptyList(),
    val error: String? = null,
)

data class NodeUpdateResult(
    val oldNode: CanonicalProxyResult,
    val newNode: CanonicalProxyResult,
)

data class SubscriptionDiffResult(
    val added: List<CanonicalProxyResult> = emptyList(),
    val updated: List<NodeUpdateResult> = emptyList(),
    val removed: List<CanonicalProxyResult> = emptyList(),
    val unchanged: List<CanonicalProxyResult> = emptyList(),
    val reordered: List<CanonicalProxyResult> = emptyList(),
    val error: String? = null,
    val failedSide: String? = null,
    val failedIndex: Int? = null,
    val failedReason: String? = null,
)

object RustBridge {
    fun parseRawSubscription(input: ByteArray): ByteArray =
        checkNotNull(RustNative.nativeParseRawSubscription(input)) { "Rust subscription parsing failed" }

    fun generateConfig(input: ByteArray): ByteArray =
        checkNotNull(RustNative.nativeGenerateConfig(input)) { "Rust configuration generation failed" }

    /** Versioned config wire; typed capture/decoding belongs to the fmt adapter. */
    fun generateOutbound(input: ByteArray): ByteArray =
        checkNotNull(RustNative.nativeGenerateOutbound(input)) { "Rust config generation failed" }


    data class PersistencePlan(val oldIndices: IntArray, val flags: IntArray, val removedIndices: IntArray)

    /** One JNI call over exact Bean projections; no URI conversion or candidate wire limits. */
    fun planSubscription(oldNames: List<String>, oldContent: Array<ByteArray>, oldOrders: LongArray,
                         newNames: List<String>, newContent: Array<ByteArray>): PersistencePlan {
        require(oldNames.size == oldContent.size && oldNames.size == oldOrders.size && newNames.size == newContent.size)
        val raw = checkNotNull(RustNative.nativePlanSubscription(
            Array(oldNames.size) { oldNames[it].toCharArray() }, oldContent, oldOrders,
            Array(newNames.size) { newNames[it].toCharArray() }, newContent
        )) { "Native subscription planning failed" }
        return decodePersistencePlan(raw, oldNames.size, newNames.size)
    }

    internal fun decodePersistencePlan(raw: IntArray, oldCount: Int, newCount: Int): PersistencePlan {
        check(raw.size >= 2 && raw[0] == newCount && raw[1] in 0..oldCount) { "Invalid plan header" }
        check(raw.size.toLong() == 2L + newCount.toLong() * 2 + raw[1]) { "Invalid plan size" }
        val oldIndices = IntArray(newCount)
        val flags = IntArray(newCount)
        val visited = BooleanArray(oldCount)
        for (i in 0 until newCount) {
            val old = raw[2 + i * 2]
            val change = raw[3 + i * 2]
            check(old in -1 until oldCount && change in 0..3 && (old != -1 || change == 1)) { "Invalid plan change" }
            if (old >= 0) {
                check(!visited[old]) { "Repeated plan match" }
                visited[old] = true
            }
            oldIndices[i] = old
            flags[i] = change
        }
        val removed = raw.copyOfRange(2 + newCount * 2, raw.size)
        for (old in removed) {
            check(old in 0 until oldCount && !visited[old]) { "Invalid removed index" }
            visited[old] = true
        }
        check(visited.all { it }) { "Plan omitted existing rows" }
        return PersistencePlan(oldIndices, flags, removed)
    }

    /** Fail closed before any persistence when the native key contract is invalid. */
    fun rankDedupKeys(keys: List<String>): List<Int> {
        if (keys.isEmpty()) return emptyList()
        // char[] preserves every UTF-16 unit, including unpaired surrogates.
        // No candidate framing limits or aggregate string allocation apply here.
        val ranks = checkNotNull(RustNative.nativeRankDedupKeys(Array(keys.size) { keys[it].toCharArray() })) {
            "Native dedup failed"
        }.toList()
        check(ranks.size == keys.size) { "Native dedup count mismatch" }
        var next = 0
        ranks.forEach { rank ->
            check(rank in 0..next) { "Invalid native dedup rank" }
            if (rank == next) next++
        }
        return ranks
    }

    const val CONTRACT_VERSION: Int = 1
    const val MAX_INPUT_BYTES: Int = 1024 * 1024
    const val MAX_BATCH_ITEMS: Int = 10_000
    const val MAX_PAYLOAD_BYTES: Int = 10 * 1024 * 1024

    fun probe(input: ByteArray): RustProbeResult = decodeResponse(RustNative.nativeProbe(input))

    private fun validUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }

    fun parseProxy(uri: String): CanonicalProxyResult = if (!validUtf16(uri))
        CanonicalProxyResult(status = "INVALID_INPUT", error = "Malformed UTF-16 in proxy URI")
    else decodeProxyResponse(RustNative.nativeParseProxy(uri))

    fun decodeSubscription(text: String): CanonicalSubscriptionResult = decodeSubscriptionResponse(RustNative.nativeDecodeSubscription(text))

    internal fun framedPayloadExceedsLimit(items: List<String>, header: Boolean = false, limit: Int = MAX_PAYLOAD_BYTES): Boolean =
        framedPayloadByteCount(items, header, limit.toLong()) > limit

    internal fun framedPayloadByteCount(items: List<String>, header: Boolean = false, limit: Long = Long.MAX_VALUE): Long {
        var bytes = if (header) items.size.toString().length.toLong() + 1 else 0L
        if (bytes > limit) return bytes
        for (item in items) {
            bytes += item.length.toString().length + 1
            if (bytes > limit) return bytes
            var i = 0
            while (i < item.length) {
                val c = item[i++]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i < item.length && item[i].isLowSurrogate() -> { i++; 4 }
                    c.isSurrogate() -> 1 // JVM UTF-8 encoder replacement for malformed UTF-16.
                    else -> 3
                }
                if (bytes > limit) return bytes
            }
        }
        return bytes
    }

    fun buildLengthPrefixed(items: List<String>): String {
        val sb = StringBuilder()
        for (item in items) {
            sb.append(item.length).append(':').append(item)
        }
        return sb.toString()
    }

    fun buildFramedBatch(items: List<String>): String {
        val sb = StringBuilder()
        sb.append(items.size).append('\n')
        for (item in items) {
            sb.append(item.length).append(':').append(item)
        }
        return sb.toString()
    }

    fun parseProxyBatch(uris: List<String>): List<CanonicalProxyResult> {
        if (uris.isEmpty()) return emptyList()
        if (uris.size > MAX_BATCH_ITEMS) {
            return List(uris.size) {
                CanonicalProxyResult(status = "INVALID_INPUT", error = "Batch size exceeds limit: ${uris.size} > $MAX_BATCH_ITEMS")
            }
        }
        // JNI string conversion cannot preserve isolated UTF-16 surrogate units.
        // Replace only those entries with a safe invalid frame, retaining count/order.
        val malformed = BooleanArray(uris.size)
        val safeUris = uris.mapIndexed { index, uri ->
            if (validUtf16(uri)) uri else { malformed[index] = true; "" }
        }
        if (framedPayloadExceedsLimit(safeUris, header = true)) {
            return List(uris.size) {
                CanonicalProxyResult(status = "INVALID_INPUT", error = "Batch payload exceeds byte limit: $MAX_PAYLOAD_BYTES")
            }
        }
        val payload = buildFramedBatch(safeUris)

        val response = RustNative.nativeParseProxyBatch(payload)
        if (response.startsWith("ERROR|")) {
            return List(uris.size) {
                CanonicalProxyResult(status = "INTERNAL_ERROR", error = response.trimEnd())
            }
        }
        val newline = response.indexOf('\n')
        if (newline == -1) {
            return List(uris.size) {
                CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Malformed response header")
            }
        }
        val countHeader = response.substring(0, newline).toIntOrNull()
        if (countHeader != uris.size) {
            return List(uris.size) {
                CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Batch result count mismatch: header says $countHeader but expected ${uris.size}")
            }
        }
        val body = response.substring(newline + 1)
        val items = decodeLengthPrefixedStrict(body)
        if (items == null || items.size != uris.size) {
            return List(uris.size) {
                CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Malformed batch response framing")
            }
        }
        return items.mapIndexed { index, item ->
            if (malformed[index]) CanonicalProxyResult(status = "INVALID_INPUT", error = "Malformed UTF-16 in proxy URI")
            else decodeProxyResponse(item)
        }
    }

    fun diffSubscriptionPipeline(oldUris: List<String>, newUris: List<String>, deduplicate: Boolean): SubscriptionDiffResult {
        if (oldUris.size > MAX_BATCH_ITEMS || newUris.size > MAX_BATCH_ITEMS) {
            return SubscriptionDiffResult(error = "INVALID_INPUT: Exceeded item limit ($MAX_BATCH_ITEMS)")
        }
        if (framedPayloadExceedsLimit(oldUris) || framedPayloadExceedsLimit(newUris)) {
            return SubscriptionDiffResult(error = "INVALID_INPUT: Payload exceeds byte limit ($MAX_PAYLOAD_BYTES)")
        }
        val oldPayload = buildLengthPrefixed(oldUris)
        val newPayload = buildLengthPrefixed(newUris)

        val response = RustNative.nativeDiffSubscriptionPipeline(oldPayload, newPayload, deduplicate)
        return parseDiffResponse(response)
    }

    fun diffSubscription(oldUris: List<String>, newUris: List<String>): SubscriptionDiffResult {
        if (oldUris.size > MAX_BATCH_ITEMS || newUris.size > MAX_BATCH_ITEMS) {
            return SubscriptionDiffResult(error = "INVALID_INPUT: Exceeded item limit ($MAX_BATCH_ITEMS)")
        }
        if (framedPayloadExceedsLimit(oldUris) || framedPayloadExceedsLimit(newUris)) {
            return SubscriptionDiffResult(error = "INVALID_INPUT: Payload exceeds byte limit ($MAX_PAYLOAD_BYTES)")
        }
        val oldPayload = buildLengthPrefixed(oldUris)
        val newPayload = buildLengthPrefixed(newUris)

        val response = RustNative.nativeDiffSubscription(oldPayload, newPayload)
        return parseDiffResponse(response)
    }

    internal fun parseDiffResponse(response: String): SubscriptionDiffResult {
        if (response.startsWith("ERROR|")) {
            val parts = response.split('|')
            return SubscriptionDiffResult(
                error = response,
                failedSide = parts.getOrNull(2),
                failedIndex = parts.getOrNull(3)?.toIntOrNull(),
                failedReason = parts.getOrNull(4),
            )
        }

        val sections = decodeLengthPrefixedStrict(response)
        if (sections == null || sections.size != 5) {
            return SubscriptionDiffResult(error = "INTERNAL_ERROR: Expected exactly 5 sections, got ${sections?.size}")
        }

        val addedRaw = decodeLengthPrefixedStrict(sections[0])
            ?: return SubscriptionDiffResult(error = "INTERNAL_ERROR: Malformed added section")
        val updatedRaw = decodeLengthPrefixedStrict(sections[1])
            ?: return SubscriptionDiffResult(error = "INTERNAL_ERROR: Malformed updated section")
        val removedRaw = decodeLengthPrefixedStrict(sections[2])
            ?: return SubscriptionDiffResult(error = "INTERNAL_ERROR: Malformed removed section")
        val unchangedRaw = decodeLengthPrefixedStrict(sections[3])
            ?: return SubscriptionDiffResult(error = "INTERNAL_ERROR: Malformed unchanged section")
        val reorderedRaw = decodeLengthPrefixedStrict(sections[4])
            ?: return SubscriptionDiffResult(error = "INTERNAL_ERROR: Malformed reordered section")

        if (updatedRaw.size % 2 != 0) {
            return SubscriptionDiffResult(error = "INTERNAL_ERROR: Updated section has odd number of items (${updatedRaw.size})")
        }

        val added = addedRaw.map { decodeProxyResponse(it) }
        val updated = updatedRaw.chunked(2).map {
            NodeUpdateResult(decodeProxyResponse(it[0]), decodeProxyResponse(it[1]))
        }
        val removed = removedRaw.map { decodeProxyResponse(it) }
        val unchanged = unchangedRaw.map { decodeProxyResponse(it) }
        val reordered = reorderedRaw.map { decodeProxyResponse(it) }

        if (sequenceOf(added, removed, unchanged, reordered).flatten().any { it.status != "SUCCESS" }
            || updated.any { it.oldNode.status != "SUCCESS" || it.newNode.status != "SUCCESS" }) {
            return SubscriptionDiffResult(error = "INTERNAL_ERROR: Invalid node in diff response")
        }

        return SubscriptionDiffResult(
            added = added,
            updated = updated,
            removed = removed,
            unchanged = unchanged,
            reordered = reordered,
        )
    }

    fun disambiguateNames(uris: List<String>): List<CanonicalProxyResult> {
        if (uris.isEmpty()) return emptyList()
        if (uris.size > MAX_BATCH_ITEMS) {
            return List(uris.size) { CanonicalProxyResult(status = "INVALID_INPUT", error = "Item count limit exceeded") }
        }
        if (framedPayloadExceedsLimit(uris)) {
            return List(uris.size) { CanonicalProxyResult(status = "INVALID_INPUT", error = "Byte limit exceeded") }
        }
        val payload = buildLengthPrefixed(uris)
        val response = RustNative.nativeDisambiguateNames(payload)
        if (response.startsWith("ERROR|")) {
            return List(uris.size) { CanonicalProxyResult(status = "INTERNAL_ERROR", error = response) }
        }
        val items = decodeLengthPrefixedStrict(response)
            ?: return List(uris.size) { CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Malformed response framing") }
        return items.map { decodeProxyResponse(it) }
    }

    fun dedupByEndpoint(uris: List<String>): List<CanonicalProxyResult> {
        if (uris.isEmpty()) return emptyList()
        if (uris.size > MAX_BATCH_ITEMS) {
            return List(uris.size) { CanonicalProxyResult(status = "INVALID_INPUT", error = "Item count limit exceeded") }
        }
        if (framedPayloadExceedsLimit(uris)) {
            return List(uris.size) { CanonicalProxyResult(status = "INVALID_INPUT", error = "Byte limit exceeded") }
        }
        val payload = buildLengthPrefixed(uris)
        val response = RustNative.nativeDedupByEndpoint(payload)
        if (response.startsWith("ERROR|")) {
            return List(uris.size) { CanonicalProxyResult(status = "INTERNAL_ERROR", error = response) }
        }
        val items = decodeLengthPrefixedStrict(response)
            ?: return List(uris.size) { CanonicalProxyResult(status = "INTERNAL_ERROR", error = "Malformed framing in dedup response") }
        return items.map { decodeProxyResponse(it) }
    }

    fun getDisplayName(protocol: String, server: String, port: Int, name: String): String {
        return RustNative.nativeGetDisplayName(protocol, server, port, name)
    }

    internal fun decodeLengthPrefixedStrict(buffer: String): List<String>? {
        if (buffer.isEmpty()) return emptyList()
        val list = mutableListOf<String>()
        var idx = 0
        while (idx < buffer.length) {
            val colon = buffer.indexOf(':', idx)
            if (colon == -1 || colon == idx) return null
            val lenStr = buffer.substring(idx, colon)
            if (!lenStr.all { it in '0'..'9' }) return null
            val len = lenStr.toIntOrNull() ?: return null
            val start = colon + 1
            // Check remaining capacity before addition, which can overflow Int.
            if (len > buffer.length - start) return null
            val end = start + len
            list.add(buffer.substring(start, end))
            idx = end
        }
        if (idx != buffer.length) return null
        return list
    }

    internal fun decodeLengthPrefixed(buffer: String): List<String> {
        return decodeLengthPrefixedStrict(buffer) ?: emptyList()
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
        if (fields.size != 38) {
            return CanonicalProxyResult(
                status = "INTERNAL_ERROR",
                error = "Expected exactly 38 fields for SUCCESS, got ${fields.size}"
            )
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
            sni = fields[7],
            alpn = fields[8],
            allowInsecure = fields[9] == "1",
            disableSNI = fields[10] == "1",
            transportType = fields[11],
            transportHost = fields[12],
            transportPath = fields[13],
            congestionControl = fields[14],
            udpRelayMode = fields[15],
            authPayload = fields[16],
            serverPorts = fields[17],
            obfsType = fields[18],
            obfsPassword = fields[19],
            uploadMbps = fields[20].toIntOrNull() ?: 0,
            downloadMbps = fields[21].toIntOrNull() ?: 0,
            certificates = fields[22],
            utlsFingerprint = fields[23],
            realityPubKey = fields[24],
            realityShortId = fields[25],
            earlyDataHeaderName = fields[26],
            wsMaxEarlyData = fields[27].toIntOrNull() ?: 0,
            packetEncoding = fields[28].toIntOrNull() ?: 0,
            disableChromeParrot = fields[29] == "1",
            bbrProfile = fields[30],
            hopIntervalMax = fields[31].toIntOrNull() ?: 0,
            obfsMinPacketSize = fields[32].toIntOrNull() ?: 512,
            obfsMaxPacketSize = fields[33].toIntOrNull() ?: 1200,
            tlsEnabled = fields[34] == "1",
            serviceName = fields[35],
            alterId = fields[36].toIntOrNull() ?: 0,
            encryption = fields[37],
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
    external fun nativeParseRawSubscriptionOptimized(input: ByteArray): ByteArray?
    init {
        System.loadLibrary("vialen_core")
    }

    @JvmStatic
    external fun nativeGenerateOutbound(input: ByteArray): ByteArray?

    @JvmStatic
    external fun nativeGenerateConfig(input: ByteArray): ByteArray?
    external fun nativeParseRawSubscription(input: ByteArray): ByteArray?

    @JvmStatic
    external fun nativePlanSubscription(oldNames: Array<CharArray>, oldContent: Array<ByteArray>, oldOrders: LongArray,
                                        newNames: Array<CharArray>, newContent: Array<ByteArray>): IntArray?

    @JvmStatic
    external fun nativeRankDedupKeys(keys: Array<CharArray>): IntArray?

    @JvmStatic
    external fun nativeProbe(input: ByteArray): ByteArray

    @JvmStatic
    external fun nativeParseProxy(uri: String): String

    @JvmStatic
    external fun nativeParseProxyBatch(payload: String): String

    @JvmStatic
    external fun nativeDecodeSubscription(text: String): String

    @JvmStatic
    external fun nativeDiffSubscription(oldPayload: String, newPayload: String): String

    @JvmStatic
    external fun nativeDiffSubscriptionPipeline(oldPayload: String, newPayload: String, deduplicate: Boolean): String

    @JvmStatic
    external fun nativeDisambiguateNames(payload: String): String

    @JvmStatic
    external fun nativeDedupByEndpoint(payload: String): String

    @JvmStatic
    external fun nativeGetDisplayName(protocol: String, server: String, port: Int, name: String): String
}
