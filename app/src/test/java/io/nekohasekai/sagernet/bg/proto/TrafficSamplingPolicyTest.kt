package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficSamplingPolicyTest {
    @Test fun foregroundUsesOneSecondWithOrWithoutStatistics() {
        assertEquals(1_000L, TrafficSampling.interval(true, true))
        assertEquals(1_000L, TrafficSampling.interval(true, false))
    }

    @Test fun backgroundStatisticsUseThirtySeconds() {
        assertEquals(30_000L, TrafficSampling.interval(false, true))
    }

    @Test fun noDemandHasNoTimer() {
        assertNull(TrafficSampling.interval(false, false))
    }
}
