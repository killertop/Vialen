package io.nekohasekai.sagernet.ui.state

/** Main-thread binding identity, including repeated binds of the same database ID. */
class BindingGeneration {
    private var current = 0L
    fun next(): Long = ++current
    fun accepts(token: Long): Boolean = token == current
}
