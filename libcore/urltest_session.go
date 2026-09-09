package libcore

import (
	"context"
	"io"
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
		i = mainInstance
	}
	var tracker adapter.ConnectionTracker
	var client *http.Client
	if i != nil {
		if i.v2api != nil {
			tracker = i.v2api.StatsService()
		}
		client = boxapi.CreateProxyHttpClient(i.Box, tracker)
	} else {
		client = boxapi.CreateProxyHttpClient(nil, nil)
	}
	return s.runClient(client, link, timeout)
}

func (s *UrlTestSession) runClient(client *http.Client, link string, timeout int32) (int32, error) {
	transport := client.Transport
	// speedtest still owns its timeout, RTT measurement, redirects and traces.
	// Wrap only request cancellation, retaining the actual proxy-aware dialer.
	client.Transport = &urlTestTransport{RoundTripper: transport, ctx: s.ctx}
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
