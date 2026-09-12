package libcore

import (
	"context"
	"io"
	"net"
	"net/http"

	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"libcore/boxapi"
	"libcore/device"
)

// UrlTestSession cancels a test independently of box startup and cleanup. Cancel
// is safe before Run, during the request, and after Run, including repeated calls.
type UrlTestSession struct {
	ctx    context.Context
	cancel context.CancelFunc
}

func NewUrlTestSession() *UrlTestSession {
	ctx, cancel := context.WithCancel(context.Background())
	return &UrlTestSession{ctx: ctx, cancel: cancel}
}

func (s *UrlTestSession) Cancel() { s.cancel() }

func (s *UrlTestSession) Run(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("UrlTestSession.Run", func(e error) { err = e })
	if err = s.ctx.Err(); err != nil {
		return
	}
	if i == nil {
		i = mainInstanceSnapshot()
	}
	var tracker adapter.ConnectionTracker
	var client *http.Client
	if i != nil {
		operationContext, done, operationError := i.beginOperation(true)
		if operationError != nil {
			return 0, operationError
		}
		defer done()
		// Closing the box cancels active tests before waiting for them. Neither
		// session cancellation nor Close needs a lock held by the HTTP request.
		requestContext, cancel := contextForBoxOperation(s.ctx, operationContext)
		defer cancel()
		if stats := i.statsSnapshot(); stats != nil {
			tracker = stats.StatsService()
		}
		client = boxapi.CreateProxyHttpClient(i.Box, tracker)
		i.trackHTTPDial(client.Transport.(*http.Transport))
		return s.runClientContext(requestContext, client, link, timeout)
	} else {
		client = boxapi.CreateProxyHttpClient(nil, nil)
	}
	return s.runClient(client, link, timeout)
}

func (s *UrlTestSession) runClient(client *http.Client, link string, timeout int32) (int32, error) {
	return s.runClientContext(s.ctx, client, link, timeout)
}

func (s *UrlTestSession) runClientContext(ctx context.Context, client *http.Client, link string, timeout int32) (int32, error) {
	transport := client.Transport
	// speedtest still owns its timeout, RTT measurement, redirects and traces.
	// Wrap only request cancellation, retaining the actual proxy-aware dialer.
	client.Transport = &urlTestTransport{RoundTripper: transport, ctx: ctx}
	defer client.CloseIdleConnections()
	return speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
}

type urlTestTransport struct {
	http.RoundTripper
	ctx context.Context
}

func (t *urlTestTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	ctx, cancel := context.WithCancel(req.Context())
	stop := context.AfterFunc(t.ctx, cancel)

	if t.ctx.Err() != nil {
		cancel()
	}
	resp, err := t.RoundTripper.RoundTrip(req.WithContext(ctx))
	if err != nil {
		stop()
		cancel()
		return resp, err
	}
	resp.Body = &urlTestBody{ReadCloser: resp.Body, cleanup: func() { stop(); cancel() }}
	return resp, nil
}

func (t *urlTestTransport) CloseIdleConnections() {
	if closer, ok := t.RoundTripper.(interface{ CloseIdleConnections() }); ok {
		closer.CloseIdleConnections()
	}
}

type urlTestBody struct {
	io.ReadCloser
	cleanup func()
}

func (b *urlTestBody) Close() error { err := b.ReadCloser.Close(); b.cleanup(); return err }

func contextForBoxOperation(parent, operation context.Context) (context.Context, func()) {
	ctx, cancel := context.WithCancel(parent)
	stop := context.AfterFunc(operation, cancel)
	if operation.Err() != nil {
		cancel()
	}
	return ctx, func() { stop(); cancel() }
}

// Transport can return a canceled request before its DialContext goroutine
// exits, and deliberately detaches the dial from request cancellation. Track
// and cancel each native dial separately so Close waits for its actual exit.
func (b *BoxInstance) trackHTTPDial(transport *http.Transport) {
	dial := transport.DialContext
	transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
		operationContext, done, err := b.beginOperation(true)
		if err != nil {
			return nil, err
		}
		defer done()
		dialContext, cancel := contextForBoxOperation(ctx, operationContext)
		defer cancel()
		conn, err := dial(dialContext, network, address)
		if conn != nil && operationContext.Err() != nil {
			_ = conn.Close()
			return nil, operationContext.Err()
		}
		return conn, err
	}
}
