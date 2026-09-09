package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.TrafficUpdater
import org.junit.Assert.*
import org.junit.Test

class TrafficEfficiencyCounterTest {
    @Test fun sameMillisecondDrainsSharedTagOnceAndNeverCopiesHistoricalTotalsAsDelta() {
        var now=100L
        var tx=7L;var rx=11L;var queries=0
        val first=TrafficUpdater.TrafficLooperData("shared",tx=100,rx=200)
        val second=TrafficUpdater.TrafficLooperData("shared",tx=300,rx=400)
        val updater=TrafficUpdater(queryStats={ _,direction ->
            queries++
            if(direction=="uplink") tx.also { tx=0 } else rx.also { rx=0 }
        },items=listOf(first,second),clock={now})
        updater.updateAll()
        assertEquals(2,queries)
        assertEquals(107L,first.tx);assertEquals(211L,first.rx)
        assertEquals(307L,second.tx);assertEquals(411L,second.rx)
        assertEquals(0L,first.txRate)
        updater.updateAll()
        assertEquals(4,queries)
        assertEquals(107L,first.tx);assertEquals(307L,second.tx)
        now=1100;tx=13;rx=17;updater.updateAll()
        assertEquals(120L,first.tx);assertEquals(320L,second.tx)
        assertEquals(13L,first.txRate);assertEquals(13L,second.txRate)
    }

    @Test fun reactivatedProfileRatesUseOnlyTheNewSelectionTime() {
        var now=100L
        val item=TrafficUpdater.TrafficLooperData("proxy",ignore=true)
        val updater=TrafficUpdater(queryStats={ _,direction -> if(direction=="uplink") 1000L else 2000L },
            items=listOf(item),clock={now})
        now=300100
        item.ignore=false;updater.resetRate(item)
        now+=1000;updater.updateAll()
        assertEquals(1000L,item.txRate);assertEquals(2000L,item.rxRate)
        assertEquals(1000L,item.tx);assertEquals(2000L,item.rx)
    }
}
