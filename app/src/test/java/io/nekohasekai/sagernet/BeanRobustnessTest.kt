package io.nekohasekai.sagernet

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BeanRobustnessTest {
    @Test
    fun equalityDoesNotChangeConcurrentPersistenceBytes() {
        val enteredEquality = CountDownLatch(1)
        val releaseEquality = CountDownLatch(1)
        val left = BlockingBean(enteredEquality, releaseEquality).apply {
            initializeDefaultValues()
            name = "left"
            customOutboundJson = "{\"shared\":true}"
        }
        val right = BlockingBean().apply {
            initializeDefaultValues()
            name = "right"
            customOutboundJson = "{\"shared\":true}"
        }
        val expected = KryoConverters.serialize(left)

        val equalityFailure = AtomicReference<Throwable?>()
        val equality = Thread({
            try {
                assertEquals(left, right)
            } catch (failure: Throwable) {
                equalityFailure.set(failure)
            }
        }, "bean-equality").apply { start() }
        check(enteredEquality.await(5, TimeUnit.SECONDS)) { "Equality did not reach serialization" }
        try {
            assertArrayEquals(expected, KryoConverters.serialize(left))
        } finally {
            releaseEquality.countDown()
            equality.join(5_000)
        }
        check(!equality.isAlive) { "Equality thread did not finish" }
        equalityFailure.get()?.let { throw it }
    }

    @Test
    fun nullPayloadStillInitializesSafeDefaults() {
        val bean = KryoConverters.deserialize(SOCKSBean(), null)
        assertEquals("127.0.0.1", bean.serverAddress)
        assertEquals(1080, bean.serverPort.toInt())
        assertEquals("", bean.name)
    }

    @Test
    fun hysteriaDefaultsDoNotOverwriteAnExplicitProtocol() {
        val bean = HysteriaBean().apply {
            protocol = HysteriaBean.PROTOCOL_FAKETCP
        }

        bean.initializeDefaultValues()

        assertEquals(HysteriaBean.PROTOCOL_FAKETCP, bean.protocol)
    }

    private class BlockingBean(
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
    ) : AbstractBean() {
        override fun serialize(output: ByteBufferOutput) {
            if (Thread.currentThread().name == "bean-equality") {
                entered?.countDown()
                check(release?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting to finish equality" }
            }
            super.serialize(output)
        }

        override fun deserialize(input: ByteBufferInput) = super.deserialize(input)

        override fun clone(): BlockingBean = BlockingBean().also {
            KryoConverters.deserialize(it, KryoConverters.serialize(this))
        }
    }
}
