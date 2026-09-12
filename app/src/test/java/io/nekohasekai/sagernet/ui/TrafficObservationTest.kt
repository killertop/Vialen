package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.SagerConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficObservationTest {
    @Test fun onlyVisibleStartedTrafficSurfaceRequestsRealtimeSampling() {
        assertEquals(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND,
            TrafficObservation.connectionId(started = true, pageAllowsControls = true))
        for ((started, visible) in listOf(false to false, false to true, true to false)) {
            assertEquals(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND,
                TrafficObservation.connectionId(started, visible))
        }
    }
}
