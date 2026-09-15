package io.nekohasekai.sagernet.utils

import android.content.ComponentCallbacks2

internal object MemoryTrimPolicy {
    @Suppress("DEPRECATION")
    fun shouldCollect(sdk: Int, level: Int, lowMemory: Boolean): Boolean = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
        // BACKGROUND is still delivered on modern Android. It is an opportunity,
        // not evidence of pressure: check the current system low-memory flag.
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> lowMemory
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> sdk < 34
        else -> false
    }
}
