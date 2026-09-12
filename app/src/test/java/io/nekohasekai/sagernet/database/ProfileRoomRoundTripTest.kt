package io.nekohasekai.sagernet.database

import androidx.room.Room
import io.nekohasekai.sagernet.CoreBridgeRobolectricTestRunner
import io.nekohasekai.sagernet.core.Profile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileRoomRoundTripTest {
    @Test fun roomStoresDocumentAndDirectFormEditsRetainAdvancedFields() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val profile = Profile(id="local-id", type="vless", server="example.com", port=443,
                vless=Profile.Vless(uuid="00000000-0000-0000-0000-000000000001"),
                transport=Profile.Transport(type="ws", headers=mapOf("x-extra" to listOf("a","b"))))
            val row = ProxyEntity(groupId=1, sourceKey="provider-id").putProfile(profile)
            row.id = db.proxyDao().addProxy(row)
            val loaded = requireNotNull(db.proxyDao().getById(row.id))
            assertEquals(profile, loaded.requireProfile())
            loaded.requireBean().name="edited"
            db.proxyDao().updateProxy(loaded)
            val reopened = requireNotNull(db.proxyDao().getById(row.id))
            assertEquals(profile.copy(name="edited"), reopened.requireProfile())
            assertEquals("provider-id", reopened.sourceKey)
        } finally { db.close() }
    }
}
