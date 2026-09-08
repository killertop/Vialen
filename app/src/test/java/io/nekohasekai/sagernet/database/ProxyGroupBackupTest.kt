package io.nekohasekai.sagernet.database

import android.os.Parcel
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.byteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProxyGroupBackupTest {
    private fun group(subscription: Boolean = false) = ProxyGroup(
        id = 37L, userOrder = 9L, name = "迁移 group",
        type = if (subscription) GroupType.SUBSCRIPTION else GroupType.BASIC,
        subscription = if (subscription) SubscriptionBean().applyDefaultValues().apply {
            link = "https://example.invalid/subscription"
            autoUpdate = true
            autoUpdateDelay = 91
            lastUpdated = 1_700_000_001
            subscriptionUserinfo = "upload=1; download=2; total=3"
        } else null,
        order = 2, isSelector = true, frontProxy = 101L, landingProxy = 202L
    )

    private fun assertGroup(expected: ProxyGroup, actual: ProxyGroup) {
        assertEquals(expected.copy(subscription = null), actual.copy(subscription = null))
        assertArrayEquals(KryoConverters.serialize(expected.subscription), KryoConverters.serialize(actual.subscription))
    }

    @Test fun serializedBasicGroupPreservesAllPersistedFields() {
        val source = group()
        assertGroup(source, KryoConverters.deserialize(ProxyGroup(), KryoConverters.serialize(source)))
    }

    @Test fun serializedSubscriptionGroupPreservesAllPersistedFields() {
        val source = group(subscription = true)
        assertGroup(source, KryoConverters.deserialize(ProxyGroup(), KryoConverters.serialize(source)))
    }

    @Test fun nativeBackupParcelPreservesSelectorAndProxyReferences() {
        val source = group(subscription = true)
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            assertGroup(source, ProxyGroup.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }

    // The previously shipped v0 private-group format ended at `order`.
    private fun legacyBytes(source: ProxyGroup): ByteArray {
        val bytes = ByteArrayOutputStream()
        bytes.byteBuffer().use { out ->
            out.writeInt(0)
            out.writeLong(source.id)
            out.writeLong(source.userOrder)
            out.writeBoolean(source.ungrouped)
            out.writeString(source.name)
            out.writeInt(source.type)
            if (source.type == GroupType.SUBSCRIPTION) source.subscription!!.serializeToBuffer(out)
            out.writeInt(source.order)
        }
        return bytes.toByteArray()
    }

    @Test fun legacyBasicAndSubscriptionBackupsRemainReadable() {
        for (subscription in listOf(false, true)) {
            val source = group(subscription)
            val expected = source.copy(isSelector = false, frontProxy = -1L, landingProxy = -1L)
            assertGroup(expected, KryoConverters.deserialize(ProxyGroup(), legacyBytes(source)))
        }
    }

    @Test fun subscriptionSharingKeepsItsExistingFormat() {
        val source = group(subscription = true).apply { export = true }
        val expected = ByteArrayOutputStream()
        expected.byteBuffer().use { out ->
            out.writeInt(0)
            out.writeString(source.name)
            out.writeInt(source.type)
            source.subscription!!.serializeForShare(out)
        }
        assertArrayEquals(expected.toByteArray(), KryoConverters.serialize(source))
        val restored = KryoConverters.deserialize(ProxyGroup().apply { export = true }, expected.toByteArray())
        assertEquals(source.name, restored.name)
        assertEquals(source.subscription!!.link, restored.subscription!!.link)
        assertEquals(0L, restored.id)
        assertFalse(restored.isSelector)
        assertEquals(-1L, restored.frontProxy)
        assertEquals(-1L, restored.landingProxy)
    }
}
