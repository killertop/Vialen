package libcore

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func awaitHTTP(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("server did not observe response closure")
	}
}
func TestSubscriptionHTTPCancelHeldBody(t *testing.T) {
	for _, direct := range []bool{false, true} {
		t.Run(map[bool]string{false: "normal", true: "direct"}[direct], func(t *testing.T) {
			disconnected := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Length", "100")
				w.WriteHeader(200)
				w.(http.Flusher).Flush()
				<-r.Context().Done()
				close(disconnected)
			}))
			defer server.Close()
			client := NewHttpClient()
			defer client.Close()
			if direct {
				client.TryH3Direct()
			}
			request := client.NewRequest()
			request.SetURL(server.URL)
			request.SetResponseSizeLimit(1024)
			request.SetTimeoutMillis(2000)
			response, err := request.Execute()
			if err != nil {
				t.Fatal(err)
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				_, err := response.GetContent()
				if !errors.Is(err, context.Canceled) {
					t.Errorf("read error %v", err)
				}
			}()
			request.Cancel()
			awaitHTTP(t, done)
			awaitHTTP(t, disconnected)
		})
	}
}
func TestSubscriptionHTTPSizeLimit(t *testing.T) {
	for _, declared := range []bool{false, true} {
		t.Run(map[bool]string{false: "chunked", true: "content-length"}[declared], func(t *testing.T) {
			disconnected := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if declared {
					w.Header().Set("Content-Length", "4096")
				}
				w.WriteHeader(200)
				w.(http.Flusher).Flush()
				if !declared {
					w.Write([]byte(strings.Repeat("x", 1025)))
					w.(http.Flusher).Flush()
				}
				<-r.Context().Done()
				close(disconnected)
			}))
			defer server.Close()
			client := NewHttpClient()
			defer client.Close()
			request := client.NewRequest()
			request.SetURL(server.URL)
			request.SetResponseSizeLimit(1024)
			request.SetTimeoutMillis(2000)
			response, err := request.Execute()
			if !declared && err == nil {
				var content []byte
				content, err = response.GetContent()
				if len(content) != 0 {
					t.Error("oversize content escaped")
				}
			}
			if !errors.Is(err, errResponseTooLarge) {
				t.Fatalf("error %v", err)
			}
			awaitHTTP(t, disconnected)
		})
	}
}
func TestSubscriptionHTTPTimeoutIncludesBody(t *testing.T) {
	// Native-core fixtures can leave process-wide heap/scheduler pressure behind.
	// Run this short real-network deadline contract in a fresh copy of the same
	// (including race-instrumented) test binary; retain every 100 ms assertion.
	if os.Getenv("VIALEN_HTTP_DEADLINE_TEST_CHILD") != "1" {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestSubscriptionHTTPTimeoutIncludesBody$", "-test.count=1")
		cmd.Env = append(os.Environ(), "VIALEN_HTTP_DEADLINE_TEST_CHILD=1")
		if output, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("isolated body deadline contract failed: %v\n%s", err, output)
		}
		return
	}
	disconnected := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
		close(disconnected)
	}))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	request.SetURL(server.URL)
	request.SetTimeoutMillis(100)
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	_, err = response.GetContent()
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("error %v", err)
	}
	awaitHTTP(t, disconnected)
}
func TestDirectHTTPBodyContext(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		time.Sleep(30 * time.Millisecond)
		w.Write([]byte("complete"))
	}))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	client.TryH3Direct()
	request := client.NewRequest()
	request.SetURL(server.URL)
	request.SetTimeoutMillis(2000)
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	body, err := response.GetContent()
	if err != nil || string(body) != "complete" {
		t.Fatalf("body %q error %v", body, err)
	}
}
func TestHTTPFailurePrivacy(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403); w.Write([]byte("synthetic-secret")) }))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	request.SetURL(strings.Replace(server.URL, "http://", "http://user:synthetic-secret@", 1) + "/?token=synthetic-secret")
	_, err := request.Execute()
	if err == nil || strings.Contains(err.Error(), "synthetic-secret") {
		t.Fatalf("unsafe error %v", err)
	}
}

func TestHTTPExactLimitAndDefaultDownload(t *testing.T) {
	payload := strings.Repeat("x", 2048)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(payload)) }))
	defer server.Close()
	client := NewHttpClient()
	defer client.Close()
	request := client.NewRequest()
	request.SetURL(server.URL)
	request.SetResponseSizeLimit(2048)
	response, err := request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	body, err := response.GetContent()
	if err != nil || string(body) != payload {
		t.Fatalf("exact limit: len=%d err=%v", len(body), err)
	}
	request = client.NewRequest()
	request.SetURL(server.URL)
	response, err = request.Execute()
	if err != nil {
		t.Fatal(err)
	}
	path := t.TempDir() + "/download"
	if err = response.WriteTo(path); err != nil {
		t.Fatal(err)
	}
	saved, err := os.ReadFile(path)
	if err != nil || string(saved) != payload {
		t.Fatalf("default download: %v", err)
	}
}
