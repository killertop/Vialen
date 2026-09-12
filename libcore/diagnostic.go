package libcore

import (
	"sync"
	"time"
)

const diagnosticDuration = 10 * time.Minute

// The deadline retains time.Time's monotonic clock. It is never persisted.
// Checking it at every emission also works if a timer callback is delayed.
type diagnosticState struct {
	mu       sync.Mutex
	deadline time.Time
}

var diagnostics diagnosticState

func (d *diagnosticState) set(enabled bool, now time.Time) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if enabled {
		d.deadline = now.Add(diagnosticDuration)
	} else {
		d.deadline = time.Time{}
	}
}

func (d *diagnosticState) remaining(now time.Time) int64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.deadline.IsZero() || !now.Before(d.deadline) {
		return 0
	}
	// Round up so a still-active sub-millisecond interval is not reported off.
	return int64((d.deadline.Sub(now) + time.Millisecond - 1) / time.Millisecond)
}

// SetDiagnosticMode starts a fresh explicit ten-minute session, or cancels it.
// Android synchronizes the user's action with the background process over its private Binder.
func SetDiagnosticMode(enabled bool) { diagnostics.set(enabled, time.Now()) }

func DiagnosticRemainingMillis() int64 { return diagnostics.remaining(time.Now()) }
