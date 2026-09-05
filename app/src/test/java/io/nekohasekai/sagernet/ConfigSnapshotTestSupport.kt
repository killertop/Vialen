package io.nekohasekai.sagernet

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase

/** Keep real snapshot collection on mocked DAOs; execute the transaction callbacks. */
fun installConfigSnapshotTransactions() {
    val preferences = mockk<PublicDatabase>(relaxed = true)
    every { preferences.runInTransaction(any<Runnable>()) } answers { firstArg<Runnable>().run() }
    every { PublicDatabase.instance } returns preferences
    val profiles = mockk<SagerDatabase>(relaxed = true)
    every { profiles.runInTransaction(any<Runnable>()) } answers { firstArg<Runnable>().run() }
    every { SagerDatabase.instance } returns profiles
}
