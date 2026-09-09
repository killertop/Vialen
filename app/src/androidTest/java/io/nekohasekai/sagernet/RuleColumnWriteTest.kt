package io.nekohasekai.sagernet

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleColumnWriteTest {
    @Test fun enabledAndOrderWritesPreserveConcurrentEditsAndMissingRows() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SagerDatabase::class.java).build()
        try {
            val dao = db.rulesDao()
            val id = dao.createRule(RuleEntity(name = "Original", config = "initial", userOrder = 1))
            val edited = dao.getById(id)!!.copy(name = "Edited", config = "new config", domains = "example.org")
            dao.updateRule(edited)
            assertEquals(1, dao.updateEnabled(id, true))
            assertEquals(1, dao.updateOrder(id, 20))
            val actual = dao.getById(id)!!
            assertTrue(actual.enabled)
            assertEquals("Edited", actual.name)
            assertEquals("new config", actual.config)
            assertEquals("example.org", actual.domains)
            assertEquals(20L, actual.userOrder)
            dao.reset()
            assertEquals(0, dao.updateEnabled(id, false))
            assertEquals(0, dao.updateOrder(id, 2))
            assertTrue(dao.allRules().isEmpty())
        } finally { db.close() }
    }
}
