package libcore

import (
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestGCGateSingleFlightCooldownAndRecovery(t *testing.T) {
	var clock atomic.Int64
	var calls atomic.Int64
	entered, release := make(chan struct{}, 4), make(chan struct{}, 4)
	g := &gcGate{now: func() time.Time { return time.Unix(0, clock.Load()) }, cooldown: time.Minute,
		collect: func() { calls.Add(1); entered <- struct{}{}; <-release }}
	if !g.request() {
		t.Fatal("first request rejected")
	}
	<-entered
	var wg sync.WaitGroup
	for range 1000 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if g.request() {
				t.Error("concurrent collection")
			}
		}()
	}
	wg.Wait()
	release <- struct{}{}
	awaitGCDone(g)
	if g.request() {
		t.Fatal("cooldown bypass")
	}
	clock.Store(int64(time.Minute))
	if !g.request() {
		t.Fatal("cooldown did not expire")
	}
	<-entered
	release <- struct{}{}
	awaitGCDone(g)
	if calls.Load() != 2 {
		t.Fatal(calls.Load())
	}
	clock.Store(int64(2 * time.Minute))
	g.collect = func() { panic("synthetic") }
	if !g.request() {
		t.Fatal("request rejected")
	}
	awaitGCDone(g)
	clock.Store(int64(3 * time.Minute))
	g.collect = func() { calls.Add(1) }
	if !g.request() {
		t.Fatal("gate stuck after panic")
	}
	awaitGCDone(g)
	if calls.Load() != 3 {
		t.Fatal(calls.Load())
	}
}

// The gate's lock is the completion barrier; no timing-based sleeps.
func awaitGCDone(g *gcGate) {
	for {
		g.mu.Lock()
		busy := g.busy
		g.mu.Unlock()
		if !busy {
			return
		}
		runtime.Gosched()
	}
}
