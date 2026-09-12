package libcore

import (
	"sync"
	"time"
)

const diagnosticDuration = 10 * time.Minute

// Android uses suspend-inclusive boot time; other platforms use Go's monotonic
// clock. The deadline is never persisted. A clock failure cancels the session.
// Checking it at every emission also works if a timer callback is delayed.
type diagnosticState struct {
	mu       sync.Mutex
	deadline time.Time
	clock    func() (time.Time, error) // optional test clock, set before use
}

var diagnostics diagnosticState

func (d *diagnosticState) now() (time.Time, error) {
	if d.clock != nil {
		return d.clock()
	}
	return diagnosticNow()
}

func (d *diagnosticState) set(enabled bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.deadline = time.Time{}
	if enabled {
		if now, err := d.now(); err == nil {
			d.deadline = now.Add(diagnosticDuration)
		}
	}
}

func (d *diagnosticState) remaining() int64 {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.deadline.IsZero() {
		return 0
	}
	now, err := d.now()
	if err != nil || !now.Before(d.deadline) {
		d.deadline = time.Time{}
		return 0
	}
	// Round up so a still-active sub-millisecond interval is not reported off.
	return int64((d.deadline.Sub(now) + time.Millisecond - 1) / time.Millisecond)
}

// SetDiagnosticMode starts a fresh explicit ten-minute session, or cancels it.
// Android synchronizes the user's action with the background process over its private Binder.
func SetDiagnosticMode(enabled bool) { diagnostics.set(enabled) }

func DiagnosticRemainingMillis() int64 { return diagnostics.remaining() }
