package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/ech"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Cancel()
	SetResponseSizeLimit(bytes int64)
	SetTimeoutMillis(milliseconds int64)
	Execute() (HTTPResponse, error)
	ExecuteSubscription() *SubscriptionHTTPResult
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
	Close() error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.DisableKeepAlives = true
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = 0 // Permit TLS 1.3, including after a previous pinned policy.
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			stop := context.AfterFunc(ctx, func() { socksConn.Close() })
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			stop()
			if err != nil {
				socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
}

func (c *httpClient) NewRequest() HTTPRequest {
	ctx, cancel := context.WithCancel(context.Background())
	req := &httpRequest{httpClient: c, ctx: ctx, cancel: cancel}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request           http.Request
	ctx               context.Context
	cancel            context.CancelFunc
	responseSizeLimit int64
	timeout           time.Duration
}

// Cancel is safe before Execute and during a response body read.
func (r *httpRequest) Cancel() { r.cancel() }
func (r *httpRequest) SetResponseSizeLimit(bytes int64) {
	if bytes == 1<<63-1 {
		bytes--
	}
	r.responseSizeLimit = bytes
}
func (r *httpRequest) SetTimeoutMillis(milliseconds int64) {
	if milliseconds > 0 && milliseconds <= int64((24*time.Hour)/time.Millisecond) {
		r.timeout = time.Duration(milliseconds) * time.Millisecond
	}
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return errors.New("invalid HTTP URL")
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.GetBody = func() (io.ReadCloser, error) { return io.NopCloser(bytes.NewReader(buffer.Bytes())), nil }
	r.request.Body, _ = r.request.GetBody()
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func safeHTTPError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, errResponseTooLarge) {
		return errResponseTooLarge
	}
	var status httpStatusError
	if errors.As(err, &status) {
		return status
	}
	var urlError *url.Error
	if errors.As(err, &urlError) {
		return safeHTTPError(urlError.Err)
	}
	if errors.Is(err, context.Canceled) {
		return context.Canceled
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return context.DeadlineExceeded
	}
	return errors.New("HTTP request failed")
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	response, err := r.execute()
	if err != nil {
		return nil, safeHTTPError(err)
	}
	return response, nil
}

func (r *httpRequest) execute() (HTTPResponse, error) {
	ctx := r.ctx
	release := r.cancel
	if r.timeout > 0 {
		timed, cancel := context.WithTimeout(ctx, r.timeout)
		ctx = timed
		release = func() { cancel(); r.cancel() }
	}
	request := r.request.Clone(ctx)
	var response *http.Response
	var err error
	if r.tryH3Direct && !r.trySocks5 {
		response, err = r.doH3Direct(request)
	} else {
		response, err = r.h1h2Client.Do(request)
		if err != nil && r.tryH3Direct && errors.Is(err, errFailConnectSocks5) && ctx.Err() == nil {
			response, err = r.doH3Direct(request)
		}
	}
	if err != nil {
		if response != nil && response.Body != nil {
			response.Body.Close()
		}
		release()
		return nil, err
	}
	response.Body = &releaseBody{ReadCloser: response.Body, release: release}
	context.AfterFunc(ctx, func() { response.Body.Close() })
	if response.StatusCode != http.StatusOK {
		response.Body.Close()
		return nil, httpStatusError(response.StatusCode)
	}
	if r.responseSizeLimit > 0 && response.ContentLength > r.responseSizeLimit {
		response.Body.Close()
		return nil, errResponseTooLarge
	}
	return &httpResponse{Response: response, limit: r.responseSizeLimit, ctx: ctx}, nil
}

type releaseBody struct {
	io.ReadCloser
	once       sync.Once
	release    func()
	closeError error
}

func (b *releaseBody) Close() error {
	b.once.Do(func() { b.closeError = b.ReadCloser.Close(); b.release() })
	return b.closeError
}

// Each racing transport owns its context until its body closes. Cancel losers,
// then join them before handing the winning body to the caller.
func (r *httpRequest) doH3Direct(request *http.Request) (*http.Response, error) {
	type result struct {
		response *http.Response
		err      error
		index    int
	}
	count := 2
	if request.URL.Scheme == "http" {
		count = 1
	}
	results := make(chan result, count)
	cancels := make([]context.CancelFunc, count)
	for i := 0; i < count; i++ {
		ctx, cancel := context.WithCancel(request.Context())
		cancels[i] = cancel
		go func(index int, ctx context.Context, cancel context.CancelFunc) {
			headerTimer := time.AfterFunc(10*time.Second, cancel)
			req := request.Clone(ctx)
			if request.GetBody != nil {
				req.Body, _ = request.GetBody()
			}
			var transport http.RoundTripper
			var cleanup func()
			if index == 0 {
				tr := &http.Transport{DisableKeepAlives: true, DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
					var dialer net.Dialer
					conn, err := dialer.DialContext(ctx, network, addr)
					if err != nil {
						return nil, err
					}
					domain, _, _ := net.SplitHostPort(addr)
					configuration := ech.NewECHClientConfig(domain, &r.tls, gLocalDNSTransport)
					secured, err := configuration.Client(ctx, conn)
					if err != nil {
						conn.Close()
					}
					return secured, err
				}}
				transport = tr
				cleanup = tr.CloseIdleConnections
			} else {
				tr := &http3.Transport{TLSClientConfig: r.tls.Clone(), QUICConfig: &quic.Config{MaxIdleTimeout: time.Second}}
				transport = tr
				cleanup = func() { tr.Close() }
			}
			response, err := (&http.Client{Transport: transport}).Do(req)
			headerTimer.Stop()
			if err == nil && response.StatusCode != http.StatusOK {
				err = fmt.Errorf("HTTP status %d", response.StatusCode)
			}
			if err != nil {
				if response != nil {
					response.Body.Close()
				}
				cleanup()
				cancel()
				response = nil
			} else {
				response.Body = &releaseBody{ReadCloser: response.Body, release: func() { cancel(); cleanup() }}
			}
			results <- result{response, err, index}
		}(i, ctx, cancel)
	}
	var winner *http.Response
	var lastErr error
	for i := 0; i < count; i++ {
		got := <-results
		if got.err != nil {
			lastErr = got.err
			continue
		}
		if winner != nil {
			got.response.Body.Close()
			continue
		}
		winner = got.response
		for index, cancel := range cancels {
			if index != got.index {
				cancel()
			}
		}
	}
	if winner != nil {
		return winner, nil
	}
	if request.Context().Err() != nil {
		return nil, request.Context().Err()
	}
	return nil, lastErr
}

var errResponseTooLarge = errors.New("HTTP response exceeds size limit")

type httpResponse struct {
	*http.Response

	getContentOnce sync.Once
	content        []byte
	contentError   error
	limit          int64
	ctx            context.Context
}

func (h *httpResponse) Close() error { return h.Body.Close() }

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.Body.Close()
		var reader io.Reader = h.Body
		if h.limit > 0 {
			reader = io.LimitReader(h.Body, h.limit+1)
		}
		h.content, h.contentError = io.ReadAll(reader)
		if h.limit > 0 && int64(len(h.content)) > h.limit {
			h.content = nil
			h.contentError = errResponseTooLarge
		}
		if h.contentError != nil && !errors.Is(h.contentError, errResponseTooLarge) {
			h.content = nil
			if h.ctx != nil && h.ctx.Err() != nil {
				h.contentError = h.ctx.Err()
			} else {
				h.contentError = safeHTTPError(h.contentError)
			}
		}
	})
	return h.content, h.contentError
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	defer h.Body.Close()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	var reader io.Reader = h.Body
	if h.limit > 0 {
		reader = io.LimitReader(h.Body, h.limit+1)
	}
	n, err := io.Copy(file, reader)
	if h.limit > 0 && n > h.limit {
		return errResponseTooLarge
	}
	return err
}
