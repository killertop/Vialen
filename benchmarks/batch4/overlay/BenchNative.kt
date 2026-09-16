package io.nekohasekai.sagernet.benchmark

import android.os.*
import org.json.*
import libcore.Libcore
import io.nekohasekai.sagernet.bg.proto.TrafficUpdater

object BenchNative {
    fun run(n:Int,out:JSONObject) {
        val tags=(0 until n.coerceAtLeast(1)).map { "t$it" }
        val config=JSONObject().put("outbounds",JSONArray(tags.map { JSONObject().put("type","direct").put("tag",it) })).toString()
        val box=Libcore.newSingBoxInstance(config,null)
        var calls=0L;var bytes=0L
        try {
            box.setV2rayStats(tags.joinToString("\n"));box.start()
            val items=tags.map { TrafficUpdater.TrafficLooperData(it) }
            val updater=TrafficUpdater({tag,direction-> calls++;bytes+=tag.length+direction.length+8;box.queryStats(tag,direction)},items /* BATCH_QUERY */)
            repeat(5){updater.updateAll()};calls=0;bytes=0
            val cpu=Process.getElapsedCpuTime();val start=SystemClock.elapsedRealtimeNanos()
            repeat(100){updater.updateAll()}
            out.put("jni",JSONObject().put("effective_tags",tags.size).put("samples",100).put("calls",calls)
                .put("ascii_payload_bytes",bytes).put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
                .put("process_cpu_ms",Process.getElapsedCpuTime()-cpu).put("traffic","idle").put("path","real JNI; direct driver, no Binder"))
        } finally { box.close() }
    }
    fun gc(out:JSONObject) {
        val before=JSONObject(Libcore.benchmarkRuntimeSnapshot())
        val gateBefore=JSONObject(Libcore.benchmarkGCGateSnapshot())
        check(gateBefore.getBoolean("supported")) { "Legacy GC completion signal unavailable" }
        check(!gateBefore.getBoolean("seen")) { "GC gate was already used in this process" }
        val cpu=Process.getElapsedCpuTime();val start=SystemClock.elapsedRealtimeNanos()
        repeat(100){Libcore.forceGc()}
        val deadline=SystemClock.elapsedRealtime()+10_000
        var gateAfter:JSONObject
        do {
            gateAfter=JSONObject(Libcore.benchmarkGCGateSnapshot())
            if (gateAfter.getBoolean("seen") && !gateAfter.getBoolean("busy")) break
            check(SystemClock.elapsedRealtime()<deadline) { "GC completion timed out" }
            Thread.sleep(10)
        } while (true)
        val after=JSONObject(Libcore.benchmarkRuntimeSnapshot())
        check(after.getLong("forced_gc_cycles")>before.getLong("forced_gc_cycles")) {
            "GC gate completed without a forced GC cycle"
        }
        out.put("gc",JSONObject().put("requests",100).put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-start)/1e6)
            .put("process_cpu_ms",Process.getElapsedCpuTime()-cpu).put("kind","injected ForceGc; not system pressure")
            .put("runtime_before",before).put("runtime_after",after)
            .put("gate_before",gateBefore).put("gate_after",gateAfter))
    }
}
