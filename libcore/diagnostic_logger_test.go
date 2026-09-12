package libcore

import (
	"context"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	sblog "github.com/sagernet/sing-box/log"
)

func TestDiagnosticExistingAndFutureLoggers(t *testing.T) {
	coreLog.mu.Lock()
	oldPath := coreLog.path
	coreLog.path = filepath.Join(t.TempDir(), "neko.log")
	path := coreLog.path
	coreLog.mu.Unlock()
	t.Cleanup(func() {
		SetDiagnosticMode(false)
		coreLog.mu.Lock()
		coreLog.path = oldPath
		coreLog.mu.Unlock()
	})
	newLogger := func() sblog.ContextLogger {
		f := sblog.NewDefaultFactory(context.Background(), sblog.Formatter{}, io.Discard, "", nil, false)
		f.SetLevel(sblog.LevelInfo) // only before logger publication
		f.AttachPlatformWriter(boxPlatformLogWriter)
		if err := f.Start(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = f.Close() })
		return f.Logger()
	}
	SetDiagnosticMode(false)
	existing := newLogger()
	existing.Debug("hidden-before")
	existing.Info("normal-info")
	SetDiagnosticMode(true)
	existing.Debug("existing-debug")
	newLogger().Trace("future-trace")
	diagnostics.set(true, time.Now().Add(-diagnosticDuration))
	existing.Debug("hidden-expired")
	existing.Info("expired-info")
	SetDiagnosticMode(true)
	existing.Debug("reenabled-debug")
	SetDiagnosticMode(false)
	existing.Debug("hidden-cancelled")
	p, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"normal-info", "existing-debug", "future-trace", "expired-info", "reenabled-debug"} {
		if !strings.Contains(string(p), want) {
			t.Errorf("missing %s", want)
		}
	}
	if strings.Contains(string(p), "hidden-") {
		t.Fatalf("verbose outside session: %s", p)
	}
}
