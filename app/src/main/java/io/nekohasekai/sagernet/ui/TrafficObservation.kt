package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.SagerConnection

/** A foreground Activity with a hidden traffic surface is not a realtime consumer. */
internal object TrafficObservation {
    fun connectionId(started: Boolean, pageAllowsControls: Boolean): Int =
        if (started && pageAllowsControls) SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
        else SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND
}
