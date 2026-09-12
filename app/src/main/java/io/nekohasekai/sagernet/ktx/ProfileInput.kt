package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.InputStream

const val MAX_PROFILE_INPUT_BYTES = 16 * 1024 * 1024
const val MAX_IMPORTED_PROFILES = 10_000

/** Read one document or ZIP entry without trusting Content-Length or archive metadata. */
fun InputStream.readProfileBytes(limit: Int = MAX_PROFILE_INPUT_BYTES): ByteArray {
    require(limit in 0..MAX_PROFILE_INPUT_BYTES)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit + 1, 8192))
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count == -1) break
        require(output.size() + count <= limit) { "Import exceeds the 16 MiB document budget" }
        if (count == 0) {
            val value = read()
            if (value == -1) break
            require(output.size() < limit) { "Import exceeds the 16 MiB document budget" }
            output.write(value)
        } else output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun InputStream.readProfileText(): String = readProfileBytes().decodeToString(throwOnInvalidSequence = true)
