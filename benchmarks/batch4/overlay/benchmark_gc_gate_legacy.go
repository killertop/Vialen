package libcore

import "encoding/json"

// L0/L1 start untracked GC goroutines; no exact completion barrier exists.
func BenchmarkGCGateSnapshot() string {
    b, _ := json.Marshal(map[string]bool{"supported": false})
    return string(b)
}
