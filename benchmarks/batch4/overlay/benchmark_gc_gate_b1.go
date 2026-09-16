package libcore

import "encoding/json"

// Measurement-only completion signal for B1's asynchronous forced-GC gate.
func BenchmarkGCGateSnapshot() string {
    forcedGC.mu.Lock()
    busy, seen := forcedGC.busy, forcedGC.seen
    forcedGC.mu.Unlock()
    b, _ := json.Marshal(map[string]bool{"supported": true, "busy": busy, "seen": seen})
    return string(b)
}
