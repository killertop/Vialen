package libcore

import (
	"log"
	"runtime/debug"
	"sync"
	"time"
)

// A conservative request budget, not an empirically optimal heap/RSS target.
// There is no timer: only an eligible caller can start a collection.
const forcedGCCooldown = 5 * time.Minute

type gcGate struct {
	mu       sync.Mutex
	busy     bool
	last     time.Time
	seen     bool
	now      func() time.Time
	collect  func()
	cooldown time.Duration
}

func (g *gcGate) request() bool {
	g.mu.Lock()
	now := g.now()
	if g.busy || (g.seen && now.Sub(g.last) < g.cooldown) {
		g.mu.Unlock()
		return false
	}
	g.busy, g.seen, g.last = true, true, now
	g.mu.Unlock()
	go func() {
		defer func() {
			if recover() != nil {
				log.Print("forced GC failed")
			}
			g.mu.Lock()
			g.busy = false
			g.mu.Unlock()
		}()
		g.collect()
	}()
	return true
}

// Each Android process has its own Go runtime and gate.
var forcedGC = gcGate{now: time.Now, collect: debug.FreeOSMemory, cooldown: forcedGCCooldown}
