package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.ktx.closeQuietly
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CloseResourceCompatibilityTest {
    @Test fun closeIoFailureDoesNotReplaceTheConnectionResult() {
        Closeable { throw IOException("close failed") }.closeQuietly()
    }

    @Test fun conscryptBioNullFailureIsIgnoredOnlyForSockets() {
        val socket = object : Socket() {
            override fun close() { throw NullPointerException("bio == null") }
        }
        socket.closeQuietly()
        val failure = NullPointerException("bio == null")
        assertSame(failure, assertThrows(NullPointerException::class.java) {
            Closeable { throw failure }.closeQuietly()
        })
    }

    @Test fun unexpectedSocketFailureStillPropagates() {
        val failure = IllegalStateException("unexpected socket state")
        val socket = object : Socket() {
            override fun close() { throw failure }
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { socket.closeQuietly() })
    }
}
