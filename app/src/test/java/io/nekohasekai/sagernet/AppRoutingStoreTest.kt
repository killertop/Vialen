package io.nekohasekai.sagernet

import androidx.room.Room
import io.mockk.*
import io.nekohasekai.sagernet.database.AppRoutingStore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.utils.AppRoutingConfig
import moe.matsuri.nb4a.TempDatabase
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppRoutingStoreTest {
    private lateinit var database: PublicDatabase
    private lateinit var store: RoomPreferenceDataStore
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PublicDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomPreferenceDataStore(database.keyValuePairDao())
        mockkObject(PublicDatabase.Companion, TempDatabase.Companion)
        every { PublicDatabase.instance } returns database
        every { PublicDatabase.kvPairDao } returns database.keyValuePairDao()
        every { TempDatabase.profileCacheDao } returns database.keyValuePairDao()
        mockkObject(DataStore)
        every { DataStore.configurationStore } returns store
        every { DataStore.dirty = any() } just Runs
    }
    @After fun teardown() {
        unmockkObject(DataStore, PublicDatabase.Companion, TempDatabase.Companion)
        database.close()
    }
    @Test fun observersOnlySeeCompleteConfiguration() {
        val observed = mutableListOf<AppRoutingConfig>()
        val listener = object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
                observed.add(AppRoutingStore.read())
            }
        }
        store.registerChangeListener(listener)
        val config = AppRoutingConfig(true, false, setOf("app.two", "app.one"))
        assertTrue(AppRoutingStore.save(config, AppRoutingConfig()))
        assertEquals(listOf(config), observed)
        assertEquals(config, AppRoutingStore.read())
        assertEquals("app.one\napp.two", store.getString(Key.INDIVIDUAL))
        store.unregisterChangeListener(listener)
    }
    @Test fun staleEditorCannotOverwriteNewerConfiguration() {
        val original = AppRoutingStore.read()
        val newer = AppRoutingConfig(true, true, setOf("app.two"))
        assertTrue(AppRoutingStore.save(newer, original))
        assertFalse(AppRoutingStore.save(AppRoutingConfig(true, false, setOf("app.one")), original))
        assertEquals(newer, AppRoutingStore.read())
    }
    @Test fun disablePreservesTheExistingAppSelection() {
        val config = AppRoutingConfig(true, false, setOf("app.one"))
        AppRoutingStore.save(config)
        AppRoutingStore.save(config.copy(enabled = false), config)
        assertEquals(config.copy(enabled = false), AppRoutingStore.read())
    }
    @Test fun failedWriteRollsBackTheWholeDecision() {
        val original = AppRoutingConfig(false, true, setOf("app.one"))
        AppRoutingStore.save(original)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_routing_mode BEFORE INSERT ON KeyValuePair
            WHEN NEW.key = '${Key.BYPASS_MODE}' BEGIN SELECT RAISE(ABORT, 'fixture'); END
        """.trimIndent())
        assertNotNull(runCatching {
            AppRoutingStore.save(AppRoutingConfig(true, false, setOf("app.two")), original)
        }.exceptionOrNull())
        assertEquals(original, AppRoutingStore.read())
    }
}
