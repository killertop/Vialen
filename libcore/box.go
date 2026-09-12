package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/nekoutils"
	"log"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/protocol/group"
	"libcore/boxapi"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/constant"
	sblog "github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

// mainInstanceAccess also serializes the matching protect server's lifetime.
var mainInstanceAccess sync.Mutex
var mainInstance *BoxInstance

func mainInstanceSnapshot() *BoxInstance {
	mainInstanceAccess.Lock()
	defer mainInstanceAccess.Unlock()
	return mainInstance
}

func VersionBox() string {
	boxVer := constant.Version
	if boxVer == "unknown" || boxVer == "" {
		boxVer = "1.14.0"
	}
	version := []string{
		"sing-box: " + boxVer,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	instance := mainInstanceSnapshot()
	if instance != nil {
		ctx, done, err := instance.beginOperation(true)
		if err != nil {
			return
		}
		defer done()
		if instance.Box != nil {
			instance.Network().ResetNetwork(ctx)
		}
	}
	log.Println("Reset connections done")
}

type BoxInstance struct {
	access           sync.Mutex
	operationAccess  sync.Mutex
	operations       sync.WaitGroup
	operationContext context.Context
	cancelOperations context.CancelFunc
	closing          bool
	started          bool
	statsAccess      sync.RWMutex
	selectorAccess   sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api        *boxapi.SbV2rayServer
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}
	if options.Experimental != nil && options.Experimental.ClashAPI != nil {
		cancel()
		return nil, fmt.Errorf("Clash API and Yacd are no longer supported by Vialen; remove experimental.clash_api")
	}
	// PlatformLogWriter implicitly starts a Clash server in sing-box. Attach
	// the Android logger after construction instead, retaining the cache that
	// the platform writer previously enabled implicitly.
	if options.Experimental == nil {
		options.Experimental = &option.ExperimentalOptions{}
	}
	if options.Experimental.CacheFile == nil {
		options.Experimental.CacheFile = &option.CacheFileOptions{Enabled: true}
	}

	// create box
	// Keep the upstream unsynchronized level immutable. Platform output is
	// filtered by our process-wide monotonic diagnostic deadline. Empty output
	// uses io.Discard for Android, preventing user configs bypassing the bound.
	options.Log = &option.LogOptions{Level: "info", DisableColor: true}
	instance, err := box.New(box.Options{
		Options: options,
		Context: ctx,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}
	if factory, ok := instance.LogFactory().(sblog.ObservableFactory); ok {
		factory.AttachPlatformWriter(boxPlatformLogWriter)
	} else {
		_ = instance.Close()
		cancel()
		return nil, fmt.Errorf("core logger does not support the Android log writer")
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		err = b.Box.Start()
		if err == nil {
			b.operationAccess.Lock()
			b.started = true
			b.operationAccess.Unlock()
		}
		return err
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// Stop admitting work before cancellation and draining. Keep a closing main
	// registered until cleanup finishes so nil-instance requests cannot fall back
	// to a direct connection during teardown.
	b.operationAccess.Lock()
	b.closing = true
	cancelOperations := b.cancelOperations
	b.operationAccess.Unlock()
	defer func() {
		mainInstanceAccess.Lock()
		defer mainInstanceAccess.Unlock()
		if mainInstance == b {
			mainInstance = nil
			goServeProtect(false)
		}
	}()
	if cancelOperations != nil {
		cancelOperations()
	}
	b.operations.Wait()

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		// Start closes itself on failure. Reuse only a completed cleanup result,
		// preserving its error; a panic still requires unconfirmed-close handling.
		if completed, closeErr := b.Box.StartFailureCleanupResult(); completed {
			return closeErr
		}
		return b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	_, done, err := b.beginOperation(true)
	if err != nil {
		return
	}
	defer done()
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	_, done, err := b.beginOperation(true)
	if err != nil {
		return
	}
	defer done()
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstanceAccess.Lock()
	defer mainInstanceAccess.Unlock()
	b.operationAccess.Lock()
	defer b.operationAccess.Unlock()
	if b.closing || mainInstance == b {
		return
	}
	mainInstance = b
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	_, done, err := b.beginOperation(false)
	if err != nil {
		return
	}
	defer done()
	b.statsAccess.Lock()
	defer b.statsAccess.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = boxapi.NewSbV2rayServer(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api.StatsService())
}

// Stats remain readable after Close for TrafficLooper's final accounting.
func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	stats := b.statsSnapshot()
	if stats == nil {
		return 0
	}
	return stats.QueryStats(fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct))
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	ctx, done, err := b.beginOperation(true)
	if err != nil {
		return false
	}
	defer done()
	// Keep the previous tag, the switch, and its notification in the same order.
	// The callback may synchronously reset connections; it holds neither the
	// main-instance lock nor the operation gate needed by ResetAllConnections.
	b.selectorAccess.Lock()
	defer b.selectorAccess.Unlock()
	if b.selector == nil || ctx.Err() != nil {
		return false
	}
	previous := b.selector.Now()
	if !b.selector.SelectOutbound(tag) {
		return false
	}
	if previous != tag && ctx.Err() == nil {
		if callback := nekoutils.Selector_OnProxySelected; callback != nil {
			callback(b.selector.Tag(), tag)
		}
	}
	return true
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	session := NewUrlTestSession()
	defer session.Cancel()
	return session.Run(i, link, timeout)
}

var protectCloser io.Closer

// Caller must hold mainInstanceAccess.
func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
