package libcore

import (
	"context"
	"crypto/x509"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

func TestSubscriptionStructuredOutcomes(t *testing.T) {
	for _, tc := range []struct {
		status int
		code   string
	}{{200, "OK"}, {400, "HTTP_REJECTED"}, {401, "ACCESS_DENIED"}, {403, "ACCESS_DENIED"}, {404, "NOT_FOUND"}, {410, "NOT_FOUND"}, {408, "TIMEOUT"}, {425, "SERVER_ERROR"}, {429, "RATE_LIMITED"}, {503, "SERVER_ERROR"}} {
		for _, direct := range []bool{false, true} {
			t.Run(tc.code+http.StatusText(tc.status), func(t *testing.T) {
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.WriteHeader(tc.status)
					w.Write([]byte("synthetic-private-body"))
				}))
				defer server.Close()
				client := NewHttpClient()
				defer client.Close()
				if direct {
					client.TryH3Direct()
				}
				r := client.NewRequest()
				r.SetURL(server.URL + "/?token=synthetic-secret")
				r.SetResponseSizeLimit(100)
				got := r.ExecuteSubscription()
				if got.Code != tc.code {
					t.Fatal(got.Code)
				}
				if tc.code != "OK" && (len(got.Content) > 0 || got.Userinfo != "" || got.Disposition != "") {
					t.Fatal("error exposed payload")
				}
			})
		}
	}
	for _, tc := range []struct {
		err  error
		code string
	}{{context.Canceled, "CANCELLED"}, {context.DeadlineExceeded, "TIMEOUT"}, {errResponseTooLarge, "TOO_LARGE"}, {x509.UnknownAuthorityError{}, "TLS_REJECTED"}, {errors.New("synthetic transport"), "NETWORK_ERROR"}, {&net.DNSError{Err: "synthetic", Name: "synthetic.example.test"}, "DNS_FAILED"}, {&net.OpError{Op: "dial", Err: context.DeadlineExceeded}, "TIMEOUT"}, {&url.Error{Op: "Get", URL: "https://example.test/?token=synthetic", Err: httpStatusError(403)}, "ACCESS_DENIED"}} {
		if got := subscriptionHTTPCode(tc.err); got != tc.code {
			t.Fatal(got, tc.code)
		}
	}
}
func TestStructuredSubscriptionCancelClosesHeldBody(t *testing.T) {
	headers, closed := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "100")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		close(headers)
		<-r.Context().Done()
		close(closed)
	}))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	r := client.NewRequest()
	r.SetURL(server.URL)
	r.SetTimeoutMillis(5000)
	result := make(chan *SubscriptionHTTPResult, 1)
	go func() { result <- r.ExecuteSubscription() }()
	awaitHTTP(t, headers)
	r.Cancel()
	got := <-result
	if got.Code != "CANCELLED" || len(got.Content) != 0 {
		t.Fatal(got.Code)
	}
	awaitHTTP(t, closed)
}

func TestDirectHeaderTimeoutIsNotUserCancellation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	client.TryH3Direct()
	request := client.NewRequest()
	request.SetURL(server.URL)
	request.SetTimeoutMillis(20000)
	if got := request.ExecuteSubscription().Code; got != "TIMEOUT" {
		t.Fatal(got)
	}
}
