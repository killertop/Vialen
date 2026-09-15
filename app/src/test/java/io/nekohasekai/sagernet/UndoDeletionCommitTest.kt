package io.nekohasekai.sagernet

import android.view.View
import androidx.room.Room
import com.google.android.material.snackbar.Snackbar
import io.mockk.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(CoreBridgeRobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UndoDeletionCommitTest {
    @Test fun undoDoesNotDeleteAndRetiredCallbacksCannotCommitLaterBatch() {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val activity = mockk<ThemedActivity>()
            every { activity.resources } returns RuntimeEnvironment.getApplication().resources
            val bars = mutableListOf<Snackbar>()
            val actions = mutableListOf<View.OnClickListener>()
            val callbacks = mutableListOf<Snackbar.Callback>()
            every { activity.snackbar(any<CharSequence>()) } answers {
                mockk<Snackbar>(relaxed = true).also { bar ->
                    bars.add(bar)
                    every { bar.addCallback(capture(callbacks)) } returns bar
                    every { bar.setAction(any<Int>(), capture(actions)) } returns bar
                }
            }
            val ids = (1..3).map { db.proxyDao().addProxy(ProxyEntity(groupId = 42, userOrder = it * 10L)) }
            val batches = mutableListOf<List<Long>>()
            val undone = mutableListOf<Long>()
            val manager = UndoSnackbarManager(activity, object : UndoSnackbarManager.Interface<Long> {
                override fun undo(actions: List<Pair<Int, Long>>) { undone.addAll(actions.map { it.second }) }
                override fun commit(actions: List<Pair<Int, Long>>) {
                    val batch = actions.map { it.second }; batches.add(batch)
                    db.proxyDao().deleteProfiles(batch.map { ProfileDeletion(it, 42) })
                }
            })
            manager.remove(0 to ids[0], 1 to ids[1])
            actions.last().onClick(null)
            manager.flush()
            assertEquals(ids, db.proxyDao().getIdsByGroup(42)); assertTrue(batches.isEmpty())
            assertEquals(ids.take(2).reversed(), undone)
            manager.remove(0 to ids[0]); manager.flush()
            manager.remove(0 to ids[1])
            callbacks[0].onDismissed(bars[0], Snackbar.Callback.DISMISS_EVENT_TIMEOUT)
            assertEquals(listOf(listOf(ids[0])), batches)
            manager.flush(); manager.flush()
            assertEquals(listOf(listOf(ids[0]), listOf(ids[1])), batches)
            assertEquals(listOf(ids[2]), db.proxyDao().getIdsByGroup(42))
        } finally { db.close() }
    }
}
