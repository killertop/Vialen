package io.nekohasekai.sagernet.utils

import java.util.concurrent.CountDownLatch

/** Serialize the complete load, so an older load cannot finish after and replace a newer one. */
internal class SnapshotLoader<T : Any>(private val load: () -> T) {
    private val initial = CountDownLatch(1)
    @Volatile private var value: T? = null
    @Volatile var failure: Exception? = null
        private set

    @Synchronized fun refresh(): Boolean {
        try {
            val next = load()
            value = next
            failure = null
            return true
        } catch (error: Exception) {
            failure = error
            return false // Preserve the last usable snapshot, never publish an empty fallback.
        } finally { initial.countDown() }
    }

    fun currentOrNull(): T? = value

    fun await(): T {
        initial.await()
        return value ?: throw IllegalStateException("无法读取应用列表，请检查权限后重试", failure)
    }
}
