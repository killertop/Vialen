package io.nekohasekai.sagernet.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Persistent local Go process: contract tests exercise Go without an Android VM. */
internal class HostCoreBackend : CoreBackend {
    private val process = ProcessBuilder(checkNotNull(System.getProperty("vialen.core.host")))
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input = DataInputStream(process.inputStream.buffered())
    private val output = DataOutputStream(process.outputStream.buffered())

    init {
        Runtime.getRuntime().addShutdownHook(Thread({ process.destroy() }, "core-host-cleanup"))
    }

    @Synchronized
    override fun execute(operation: String, input: ByteArray): ByteArray {
        val op = operation.encodeToByteArray()
        output.writeInt(op.size); output.write(op)
        output.writeInt(input.size); output.write(input); output.flush()
        val status = this.input.readUnsignedByte()
        val size = this.input.readInt()
        check(size in 0..128 * 1024 * 1024) { "Invalid host test response length" }
        val result = ByteArray(size)
        this.input.readFully(result)
        check(status == 0) { result.decodeToString() }
        return result
    }
}
