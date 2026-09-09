package libcore

import (
	"context"
	"errors"
	"libcore/boxapi"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestUrlTestSessionCancelBeforeRun(t *testing.T) {
	s := NewUrlTestSession()
	s.Cancel()
	s.Cancel()
	_, err := s.Run(nil, "http://127.0.0.1:1", 10000)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
}
func TestUrlTestSessionCancelsActiveRequest(t *testing.T) {
	entered := make(chan struct{})
	exited := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { close(entered); <-r.Context().Done(); close(exited) }))
	defer server.Close()
	instance, err := NewSingBoxInstance(`{"outbounds":[{"type":"direct","tag":"proxy"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	s := NewUrlTestSession()
	result := make(chan error, 1)
	go func() {
		_, err := s.Run(instance, server.URL, 10000)
		result <- err
	}()
	select {
	case <-entered:
	case <-time.After(time.Second * 2):
		t.Fatal("request never started")
	}
	s.Cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancellation did not interrupt request")
	}
	select {
	case <-exited:
	case <-time.After(time.Second):
		t.Fatal("active request leaked")
	}
}
func TestUrlTestSessionSuccessAndCancelAfter(t *testing.T) {
	var count atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { count.Add(1); w.WriteHeader(204) }))
	defer server.Close()
	s := NewUrlTestSession()
	latency, err := s.runClient(boxapi.CreateProxyHttpClient(nil, nil), server.URL, 1000)
	if err != nil || latency < 0 || count.Load() != 2 {
		t.Fatalf("latency=%d requests=%d err=%v", latency, count.Load(), err)
	}
	s.Cancel()
	s.Cancel()
}
func TestUrlTestSessionRetainsTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer server.Close()
	s := NewUrlTestSession()
	defer s.Cancel()
	_, err := s.runClient(boxapi.CreateProxyHttpClient(nil, nil), server.URL, 40)
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("got %v", err)
	}
}
