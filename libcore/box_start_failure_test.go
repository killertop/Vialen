package libcore

import (
	"context"
	"errors"
	"fmt"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	boxService "github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"net"
	"testing"
)

func TestBoxStartFailureCanCloseAndRetry(t *testing.T) {
	held, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = held.Close() })
	port := held.Addr().(*net.TCPAddr).Port
	config := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":"mixed","tag":"listener","listen":"127.0.0.1","listen_port":%d}],"outbounds":[{"type":"direct","tag":"direct"}]}`, port)
	instance, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	if err = instance.Start(); err == nil {
		t.Fatal("occupied listener unexpectedly started")
	}
	if err = instance.Close(); err != nil {
		t.Fatalf("cleanup after failed Start: %v", err)
	}
	if err = held.Close(); err != nil {
		t.Fatal(err)
	}
	retry, err := NewSingBoxInstance(config, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = retry.Close() })
	if err = retry.Start(); err != nil {
		t.Fatalf("retry after releasing port: %v", err)
	}
	conn, err := net.Dial("tcp4", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatalf("retry listener is unavailable: %v", err)
	}
	_ = conn.Close()
	if err = retry.Close(); err != nil {
		t.Fatalf("close successful retry: %v", err)
	}
}

// Exercise the actual manager -> Box -> wrapper cleanup-error chain.
type failedCleanupService struct {
	closes   int
	closeErr error
}

func (*failedCleanupService) Type() string { return "failed-cleanup" }
func (*failedCleanupService) Tag() string  { return "failed-cleanup" }
func (*failedCleanupService) Start(adapter.StartStage) error {
	return errors.New("service start failed")
}
func (s *failedCleanupService) Close() error { s.closes++; return s.closeErr }

func TestBoxStartFailureDoesNotHideCleanupFailure(t *testing.T) {
	sentinel := errors.New("service cleanup failed")
	fixture := &failedCleanupService{closeErr: sentinel}
	registry := boxService.NewRegistry()
	boxService.Register(registry, "failed-cleanup", func(context.Context, log.ContextLogger, string, struct{}) (adapter.Service, error) {
		return fixture, nil
	})
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), registry, nekoboxAndroidCertificateProviderRegistry())
	native, err := box.New(box.Options{Context: ctx, Options: option.Options{Log: &option.LogOptions{Disabled: true}, Services: []option.Service{{Type: "failed-cleanup"}}}})
	if err != nil {
		t.Fatal(err)
	}
	instance := &BoxInstance{Box: native}
	if err = instance.Start(); err == nil {
		t.Fatal("expected failed service start")
	}
	if fixture.closes != 1 {
		t.Fatalf("automatic cleanup calls = %d", fixture.closes)
	}
	if completed, closeErr := native.StartFailureCleanupResult(); !completed || !errors.Is(closeErr, sentinel) {
		t.Fatalf("lost completed cleanup failure: %v, %v", completed, closeErr)
	}
	if err = instance.Close(); !errors.Is(err, sentinel) {
		t.Fatalf("failed cleanup must preserve its original error: %v", err)
	}
}
