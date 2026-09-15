package libcore

import (
    "encoding/json"
    "runtime"
)

// Measurement overlay only. Snapshots are outside the timed request loop.
func BenchmarkRuntimeSnapshot() string {
    var m runtime.MemStats
    runtime.ReadMemStats(&m)
    b, _ := json.Marshal(map[string]uint64{"heap_alloc":m.HeapAlloc,"heap_sys":m.HeapSys,
        "heap_released":m.HeapReleased,"gc_cycles":uint64(m.NumGC),
        "forced_gc_cycles":uint64(m.NumForcedGC),"pause_total_ns":m.PauseTotalNs})
    return string(b)
}
