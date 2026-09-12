package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

class ExportStreamErrorMappingTest {

    // This pure stream test must not depend on another test's logger mock or Android Go JNI.
    @org.junit.Before fun isolateNativeLogger() {
        io.mockk.mockkObject(io.nekohasekai.sagernet.ktx.Logs)
        io.mockk.every { io.nekohasekai.sagernet.ktx.Logs.w(any<Throwable>()) } returns Unit
    }
    @org.junit.After fun restoreNativeLogger() {
        io.mockk.unmockkObject(io.nekohasekai.sagernet.ktx.Logs)
    }

    private val errorMsg = "Failed to export."

    @Test
    fun openingFailureThrowsMappedIoException() {
        val err = assertThrows(IOException::class.java) {
            ConfigurationFragment.writeExportConfig(
                { throw FileNotFoundException("EISDIR: Is a directory") },
                "config",
                errorMsg
            )
        }
        assertEquals(errorMsg, err.message)
        assertEquals("EISDIR: Is a directory", err.cause?.message)
    }

    @Test
    fun nullStreamThrowsMappedIoException() {
        val err = assertThrows(IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ null }, "config", errorMsg)
        }
        assertEquals(errorMsg, err.message)
    }

    @Test
    fun writeFailureThrowsMappedIoException() {
        val stream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk write failed")
            }
        }
        val err = assertThrows(IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ stream }, "config", errorMsg)
        }
        assertEquals(errorMsg, err.message)
        assertEquals("disk write failed", err.cause?.message)
    }

    @Test
    fun flushFailureThrowsMappedIoException() {
        val stream = object : OutputStream() {
            override fun write(b: Int) {}
            override fun flush() {
                throw IOException("disk full on flush")
            }
        }
        val err = assertThrows(IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ stream }, "config", errorMsg)
        }
        assertEquals(errorMsg, err.message)
        assertEquals("disk full on flush", err.cause?.message)
    }

    @Test
    fun closeFailureThrowsMappedIoException() {
        val stream = object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() {
                throw IOException("storage detached on close")
            }
        }
        val err = assertThrows(IOException::class.java) {
            ConfigurationFragment.writeExportConfig({ stream }, "config", errorMsg)
        }
        assertEquals(errorMsg, err.message)
        assertEquals("storage detached on close", err.cause?.message)
    }

    @Test
    fun successfulWriteWritesEntireContent() {
        val stream = ByteArrayOutputStream()
        ConfigurationFragment.writeExportConfig({ stream }, "{\"server\":\"127.0.0.1\"}", errorMsg)
        assertEquals("{\"server\":\"127.0.0.1\"}", stream.toString(Charsets.UTF_8.name()))
    }
}
