package libcore

import (
	"strings"
	"testing"
)

func TestRemovedClashAPIRejected(t *testing.T) {
	_, err := NewSingBoxInstance(`{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090"}},"outbounds":[{"type":"direct"}]}`, nil)
	if err == nil || !strings.Contains(err.Error(), "remove experimental.clash_api") {
		t.Fatalf("expected actionable removed API error, got %v", err)
	}
}

func TestCoreWithoutDashboardStillConstructs(t *testing.T) {
	instance, err := NewSingBoxInstance(`{"outbounds":[{"type":"direct"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
}
