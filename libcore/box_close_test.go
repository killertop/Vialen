package libcore

import (
	"errors"
	"os"
	"testing"
)

func TestBoxClosePropagatesUnderlyingError(t *testing.T) {
	instance, err := NewSingBoxInstance(`{"outbounds":[{"type":"direct","tag":"direct"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	// A real already-closed native Box returns os.ErrClosed. The wrapper must
	// preserve it so Android cannot report unverified cleanup as successful.
	_ = instance.Box.Close()
	if err := instance.Close(); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("Close error = %v; want os.ErrClosed", err)
	}
	if err := instance.Close(); err != nil {
		t.Fatalf("duplicate wrapper Close error = %v; want nil", err)
	}
}
