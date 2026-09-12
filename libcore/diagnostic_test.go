package libcore

import (
	"errors"
	"sync"
	"testing"
	"time"
)

func TestDiagnosticDeadlineCancelReenable(t *testing.T) {
	now := time.Now()
	d := diagnosticState{clock: func() (time.Time, error) { return now, nil }}
	if d.remaining() != 0 {
		t.Fatal("enabled by default")
	}
	d.set(true)
	if d.remaining() != 600000 {
		t.Fatal("wrong maximum")
	}
	now = now.Add(diagnosticDuration - time.Nanosecond)
	if d.remaining() != 1 {
		t.Fatal("rounding")
	}
	now = now.Add(time.Nanosecond)
	if d.remaining() != 0 {
		t.Fatal("deadline did not expire")
	}
	d.set(true)
	d.set(false)
	if d.remaining() != 0 {
		t.Fatal("cancel failed")
	}
	d.set(true)
	now = now.Add(time.Minute)
	d.set(true)
	now = now.Add(9 * time.Minute)
	if d.remaining() != 60000 {
		t.Fatal("old deadline cancelled new session")
	}
	d.set(false)
	if d.remaining() != 0 {
		t.Fatal("second cancel failed")
	}
}

func TestDiagnosticConcurrent(t *testing.T) {
	var d diagnosticState
	var wg sync.WaitGroup
	for g := 0; g < 8; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 1000; i++ {
				d.set(i%2 == 0)
				d.remaining()
			}
		}()
	}
	wg.Wait()
}

func TestDiagnosticSuspendAndClockFailure(t *testing.T) {
	boot := time.Unix(100, 0)
	var clockErr error
	d := diagnosticState{clock: func() (time.Time, error) { return boot, clockErr }}
	d.set(true)
	// Simulate waking after eleven minutes with no intervening calls.
	boot = boot.Add(11 * time.Minute)
	if d.remaining() != 0 {
		t.Fatal("session survived suspend")
	}
	d.set(true)
	clockErr = errors.New("clock unavailable")
	if d.remaining() != 0 {
		t.Fatal("clock failure did not close session")
	}
	clockErr = nil
	if d.remaining() != 0 {
		t.Fatal("clock recovery reenabled session")
	}
	clockErr = errors.New("clock unavailable")
	d.set(true)
	clockErr = nil
	if d.remaining() != 0 {
		t.Fatal("failed start reenabled session")
	}
	d.set(true)
	if d.remaining() != 600000 {
		t.Fatal("explicit restart failed")
	}
}
