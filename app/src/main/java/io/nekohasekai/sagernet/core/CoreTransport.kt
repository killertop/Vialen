package io.nekohasekai.sagernet.core

import libcore.Libcore

internal fun interface CoreBackend {
    fun execute(operation: String, input: ByteArray): ByteArray
}

/** Coarse business calls. The host adapter exists only in the JVM test source set. */
internal object CoreTransport {
    private val backend: CoreBackend = System.getProperty("vialen.core.testBackend")?.let {
        Class.forName(it).getDeclaredConstructor().newInstance() as CoreBackend
    } ?: CoreBackend { operation, input ->
        when (operation) {
            "import" -> Libcore.coreImport(input)
            "compile" -> Libcore.coreCompile(input)
            "export" -> Libcore.coreExportProfile(input)
            "validate" -> { Libcore.coreValidateProfiles(input); "{}".encodeToByteArray() }
            "validate_rule" -> { Libcore.coreValidateRuleMatch(input); "{}".encodeToByteArray() }
            else -> error("Unknown core operation")
        }
    }

    fun execute(operation: String, input: ByteArray): ByteArray = backend.execute(operation, input)
}
