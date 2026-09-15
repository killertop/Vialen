package libcore

import (
	"context"
	"crypto/x509"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSubscriptionStructuredOutcomes(t *testing.T) {
	for _, tc := range []struct {
		status int
		code   string
	}{{200, "OK"}, {401, "HTTP_REJECTED"}, {403, "HTTP_REJECTED"}, {404, "HTTP_REJECTED"}, {408, "TEMPORARY"}, {429, "TEMPORARY"}, {503, "TEMPORARY"}} {
		t.Run(tc.code+http.StatusText(tc.status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				w.Write([]byte("synthetic-private-body"))
			}))
			defer server.Close()
			client := NewHttpClient()
			defer client.Close()
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
	for _, tc := range []struct {
		err  error
		code string
	}{{context.Canceled, "CANCELLED"}, {context.DeadlineExceeded, "TIMEOUT"}, {errResponseTooLarge, "TOO_LARGE"}, {x509.UnknownAuthorityError{}, "TLS_REJECTED"}, {errors.New("synthetic transport"), "TEMPORARY"}} {
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
