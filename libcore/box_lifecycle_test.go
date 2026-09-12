package libcore

import (
	"context"
	"errors"
	"io"
	"libcore/nekoutils"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	boxOutbound "github.com/sagernet/sing-box/adapter/outbound"
	boxService "github.com/sagernet/sing-box/adapter/service"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func startedLifecycleBox(t *testing.T) *BoxInstance {
	t.Helper()
	instance, err := NewSingBoxInstance(`{"outbounds":[{"type":"direct","tag":"proxy"}],"experimental":{"cache_file":{"enabled":false}}}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	return instance
}

// Run the public methods against initialized, started native boxes. This also
// covers a stale SetAsMain arriving after its own Close, and two owners racing.
func TestMainInstanceResetCloseConcurrent(t *testing.T) {
	output := log.Writer()
	log.SetOutput(io.Discard)
	defer log.SetOutput(output)
	for cycle := 0; cycle < 20; cycle++ {
		first, second := startedLifecycleBox(t), startedLifecycleBox(t)
		first.SetAsMain()
		start := make(chan struct{})
		var workers sync.WaitGroup
		for _, action := range []func(){
			func() {
				for n := 0; n < 100; n++ {
					first.SetAsMain()
					ResetAllConnections(true)
				}
			},
			func() {
				for n := 0; n < 100; n++ {
					second.SetAsMain()
					ResetAllConnections(true)
				}
			},
			func() { _ = first.Close() },
			func() { _ = second.Close() },
		} {
			workers.Add(1)
			go func(action func()) { defer workers.Done(); <-start; action() }(action)
		}
		close(start)
		workers.Wait()
		if mainInstanceSnapshot() != nil {
			t.Fatal("closed instance registered again")
		}
	}
}

func TestMainInstanceCloseCancelsActiveUrlTests(t *testing.T) {
	for _, mode := range []string{"legacy-main", "session-main", "session-explicit"} {
		t.Run(mode, func(t *testing.T) {
			entered, exited := make(chan struct{}), make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				close(entered)
				<-r.Context().Done()
				close(exited)
			}))
			defer server.Close()
			instance := startedLifecycleBox(t)
			instance.SetAsMain()
			session := NewUrlTestSession()
			defer session.Cancel()
			result := make(chan error, 1)
			go func() {
				var err error
				switch mode {
				case "legacy-main":
					_, err = UrlTest(nil, server.URL, 10000)
				case "session-main":
					_, err = session.Run(nil, server.URL, 10000)
				default:
					_, err = session.Run(instance, server.URL, 10000)
				}
				result <- err
			}()
			select {
			case <-entered:
			case <-time.After(2 * time.Second):
				t.Fatal("request never entered native dialer")
			}
			closed := make(chan error, 1)
			go func() { closed <- instance.Close() }()
			select {
			case err := <-closed:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(2 * time.Second):
				t.Fatal("Close waited for the full URL-test timeout")
			}
			select {
			case err := <-result:
				if !errors.Is(err, context.Canceled) {
					t.Fatalf("request error = %v", err)
				}
			case <-time.After(time.Second):
				t.Fatal("Close left the URL test running")
			}
			select {
			case <-exited:
			case <-time.After(time.Second):
				t.Fatal("remote request was not canceled")
			}
			if _, err := session.Run(instance, server.URL, 1000); !errors.Is(err, os.ErrClosed) {
				t.Fatalf("closed explicit instance accepted a new test: %v", err)
			}
		})
	}
}

type waitingCloseService struct{ entered, release chan struct{} }

func (*waitingCloseService) Type() string                   { return "waiting-close" }
func (*waitingCloseService) Tag() string                    { return "waiting-close" }
func (*waitingCloseService) Start(adapter.StartStage) error { return nil }
func (s *waitingCloseService) Close() error                 { close(s.entered); <-s.release; return nil }

type countedProtectCloser struct{ closes atomic.Int32 }

func (c *countedProtectCloser) Close() error { c.closes.Add(1); return nil }

func TestMainInstanceClosingRejectsFallbackAndPreservesReplacement(t *testing.T) {
	fixture := &waitingCloseService{entered: make(chan struct{}), release: make(chan struct{})}
	var release sync.Once
	defer release.Do(func() { close(fixture.release) })
	registry := boxService.NewRegistry()
	boxService.Register(registry, "waiting-close", func(context.Context, sblog.ContextLogger, string, struct{}) (adapter.Service, error) {
		return fixture, nil
	})
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), registry, nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)
	native, err := box.New(box.Options{Context: ctx, Options: option.Options{Log: &option.LogOptions{Disabled: true}, Services: []option.Service{{Type: "waiting-close"}}}})
	if err != nil {
		t.Fatal(err)
	}
	previous := &BoxInstance{Box: native}
	t.Cleanup(func() { _ = previous.Close() })
	if err := previous.Start(); err != nil {
		t.Fatal(err)
	}
	previous.SetAsMain()
	closed := make(chan error, 1)
	go func() { closed <- previous.Close() }()
	select {
	case <-fixture.entered:
	case <-time.After(2 * time.Second):
		t.Fatal("native close never entered")
	}
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { requests.Add(1); w.WriteHeader(204) }))
	defer server.Close()
	session := NewUrlTestSession()
	defer session.Cancel()
	if _, err := UrlTest(nil, server.URL, 1000); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("legacy fallback while closing: %v", err)
	}
	if _, err := session.Run(nil, server.URL, 1000); !errors.Is(err, os.ErrClosed) {
		t.Fatalf("session fallback while closing: %v", err)
	}
	if requests.Load() != 0 {
		t.Fatal("closing main caused a direct network request")
	}
	replacement := startedLifecycleBox(t)
	replacement.SetAsMain()
	// Darwin has no protect socket; a real io.Closer records whether finishing
	// the old native close erroneously tears down the replacement's server.
	counter := &countedProtectCloser{}
	mainInstanceAccess.Lock()
	goServeProtect(false)
	protectCloser = counter
	mainInstanceAccess.Unlock()
	previous.SetAsMain()
	release.Do(func() { close(fixture.release) })
	select {
	case err := <-closed:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("previous close did not finish")
	}
	if mainInstanceSnapshot() != replacement || counter.closes.Load() != 0 {
		t.Fatal("previous close replaced or stopped the new main")
	}
	if err := replacement.Close(); err != nil {
		t.Fatal(err)
	}
	if mainInstanceSnapshot() != nil || counter.closes.Load() != 1 {
		t.Fatal("replacement did not close its own protect server exactly once")
	}
}

type waitingResetOutbound struct {
	boxOutbound.Adapter
	entered, canceled, release chan struct{}
	closes                     atomic.Int32
}

func (*waitingResetOutbound) DialContext(context.Context, string, M.Socksaddr) (net.Conn, error) {
	return nil, os.ErrInvalid
}
func (*waitingResetOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}
func (o *waitingResetOutbound) InterfaceUpdated(ctx context.Context) {
	close(o.entered)
	<-ctx.Done()
	close(o.canceled)
	<-o.release
}
func (o *waitingResetOutbound) Close() error { o.closes.Add(1); return nil }

func TestMainInstanceCloseCancelsAndDrainsReset(t *testing.T) {
	fixture := &waitingResetOutbound{
		Adapter: boxOutbound.NewAdapter("waiting-reset", "proxy", []string{"tcp"}, nil),
		entered: make(chan struct{}), canceled: make(chan struct{}), release: make(chan struct{}),
	}
	var release sync.Once
	defer release.Do(func() { close(fixture.release) })
	registry := boxOutbound.NewRegistry()
	boxOutbound.Register(registry, "waiting-reset", func(context.Context, adapter.Router, sblog.ContextLogger, string, struct{}) (adapter.Outbound, error) {
		return fixture, nil
	})
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), registry, nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)
	native, err := box.New(box.Options{Context: ctx, Options: option.Options{Log: &option.LogOptions{Disabled: true}, Outbounds: []option.Outbound{{Type: "waiting-reset", Tag: "proxy"}}}})
	if err != nil {
		t.Fatal(err)
	}
	instance := &BoxInstance{Box: native}
	t.Cleanup(func() { _ = instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	instance.SetAsMain()
	resetDone := make(chan struct{})
	go func() { ResetAllConnections(true); close(resetDone) }()
	select {
	case <-fixture.entered:
	case <-time.After(time.Second):
		t.Fatal("native reset listener never entered")
	}
	closeDone := make(chan error, 1)
	go func() { closeDone <- instance.Close() }()
	select {
	case <-fixture.canceled:
	case <-time.After(time.Second):
		t.Fatal("Close did not cancel the in-flight reset context")
	}
	ResetAllConnections(true) // Must be rejected; a second listener entry would panic.
	if fixture.closes.Load() != 0 {
		t.Fatal("native outbound closed while reset still used it")
	}
	select {
	case <-closeDone:
		t.Fatal("Close returned before reset finished")
	default:
	}
	release.Do(func() { close(fixture.release) })
	select {
	case <-resetDone:
	case <-time.After(time.Second):
		t.Fatal("reset failed to exit")
	}
	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not finish after reset")
	}
	if fixture.closes.Load() != 1 {
		t.Fatalf("native close count = %d", fixture.closes.Load())
	}
}

type waitingDialOutbound struct {
	boxOutbound.Adapter
	entered, canceled, release chan struct{}
	closes                     atomic.Int32
}

func (o *waitingDialOutbound) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	close(o.entered)
	<-ctx.Done()
	close(o.canceled)
	<-o.release
	return nil, ctx.Err()
}
func (*waitingDialOutbound) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}
func (o *waitingDialOutbound) Close() error { o.closes.Add(1); return nil }

func TestMainInstanceCloseDrainsDetachedHTTPDial(t *testing.T) {
	fixture := &waitingDialOutbound{
		Adapter: boxOutbound.NewAdapter("waiting-dial", "proxy", []string{"tcp"}, nil),
		entered: make(chan struct{}), canceled: make(chan struct{}), release: make(chan struct{}),
	}
	var release sync.Once
	defer release.Do(func() { close(fixture.release) })
	registry := boxOutbound.NewRegistry()
	boxOutbound.Register(registry, "waiting-dial", func(context.Context, adapter.Router, sblog.ContextLogger, string, struct{}) (adapter.Outbound, error) {
		return fixture, nil
	})
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), registry, nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)
	native, err := box.New(box.Options{Context: ctx, Options: option.Options{Log: &option.LogOptions{Disabled: true}, Outbounds: []option.Outbound{{Type: "waiting-dial", Tag: "proxy"}}}})
	if err != nil {
		t.Fatal(err)
	}
	instance := &BoxInstance{Box: native}
	t.Cleanup(func() { _ = instance.Close() })
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	instance.SetAsMain()
	testDone := make(chan error, 1)
	go func() { _, err := UrlTest(nil, "http://127.0.0.1:1", 10000); testDone <- err }()
	select {
	case <-fixture.entered:
	case <-time.After(time.Second):
		t.Fatal("native dial never entered")
	}
	closeDone := make(chan error, 1)
	go func() { closeDone <- instance.Close() }()
	select {
	case <-fixture.canceled:
	case <-time.After(time.Second):
		t.Fatal("Close did not cancel the detached native dial")
	}
	select {
	case err := <-testDone:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("URL test failed to observe cancellation")
	}
	// HTTP has returned, but the underlying native dial has not. Closing it now
	// would reproduce the use-after-close window hidden by request-level joins.
	if fixture.closes.Load() != 0 {
		t.Fatal("native outbound closed while dial still used it")
	}
	select {
	case <-closeDone:
		t.Fatal("Close returned before native dial exited")
	default:
	}
	release.Do(func() { close(fixture.release) })
	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not finish after dial exit")
	}
	if fixture.closes.Load() != 1 {
		t.Fatalf("native close count = %d", fixture.closes.Load())
	}
}

func TestSelectorOutboundNotifiesOnlySuccessfulChanges(t *testing.T) {
	instance, err := NewSingBoxInstance(`{"outbounds":[{"type":"selector","tag":"selected","outbounds":["first","second"],"default":"first"},{"type":"direct","tag":"first"},{"type":"direct","tag":"second"}],"experimental":{"cache_file":{"enabled":false}}}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	instance.SetAsMain()
	previousCallback := nekoutils.Selector_OnProxySelected
	defer func() { nekoutils.Selector_OnProxySelected = previousCallback }()
	var selected []string
	nekoutils.Selector_OnProxySelected = func(selector, tag string) {
		selected = append(selected, selector+"/"+tag)
		ResetAllConnections(true) // The Android callback reenters the native wrapper.
	}
	if !instance.SelectOutbound("first") || len(selected) != 0 {
		t.Fatal("same-tag selection notified")
	}
	if instance.SelectOutbound("missing") || len(selected) != 0 {
		t.Fatal("invalid selection notified")
	}
	changed := make(chan bool, 1)
	go func() { changed <- instance.SelectOutbound("second") }()
	select {
	case ok := <-changed:
		if !ok {
			t.Fatal("valid selector change failed")
		}
	case <-time.After(time.Second):
		t.Fatal("selector callback deadlocked while resetting connections")
	}
	if len(selected) != 1 || selected[0] != "selected/second" || instance.selector.Now() != "second" {
		t.Fatalf("notification did not match actual selector: %v", selected)
	}
	if !instance.SelectOutbound("second") || len(selected) != 1 {
		t.Fatal("repeated selection notified")
	}
	nekoutils.Selector_OnProxySelected = nil
	if !instance.SelectOutbound("first") || instance.selector.Now() != "first" {
		t.Fatal("missing callback prevented selection")
	}
	nekoutils.Selector_OnProxySelected = func(selector, tag string) { selected = append(selected, selector+"/"+tag) }
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	if instance.SelectOutbound("second") || len(selected) != 1 {
		t.Fatal("closed selector accepted or notified a change")
	}
}
