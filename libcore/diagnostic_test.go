package libcore

import (
	"sync"
	"testing"
	"time"
)

func TestDiagnosticDeadlineCancelReenable(t *testing.T) {
	var d diagnosticState
	now := time.Now()
	if d.remaining(now) != 0 {
		t.Fatal("enabled by default")
	}
	d.set(true, now)
	if d.remaining(now) != 600000 {
		t.Fatal("wrong maximum")
	}
	if d.remaining(now.Add(diagnosticDuration-time.Nanosecond)) != 1 {
		t.Fatal("rounding")
	}
	if d.remaining(now.Add(diagnosticDuration)) != 0 {
		t.Fatal("deadline did not expire")
	}
	d.set(false, now)
	if d.remaining(now) != 0 {
		t.Fatal("cancel failed")
	}
	later := now.Add(time.Minute)
	d.set(true, later)
	if d.remaining(now.Add(diagnosticDuration)) != 60000 {
		t.Fatal("old deadline cancelled new session")
	}
	d.set(false, later)
	if d.remaining(later) != 0 {
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
				d.set(i%2 == 0, time.Now())
				d.remaining(time.Now())
			}
		}()
	}
	wg.Wait()
}
