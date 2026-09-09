package libcore

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TrafficLooper samples once after Box.Close: counters must survive teardown,
// and QueryStats must still consume each retained delta exactly once.
func TestUrlTestStatsRetainedAfterBoxClose(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	instance, err := NewSingBoxInstance(`{"outbounds":[{"type":"direct","tag":"proxy"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	instance.SetV2rayStats("proxy")
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}
	session := NewUrlTestSession()
	defer session.Cancel()
	if _, err := session.Run(instance, server.URL, 1000); err != nil {
		t.Fatal(err)
	}
	if err := instance.Close(); err != nil {
		t.Fatal(err)
	}
	for _, direction := range []string{"uplink", "downlink"} {
		if count := instance.QueryStats("proxy", direction); count <= 0 {
			t.Fatalf("no retained %s bytes after box close: %d", direction, count)
		}
		if count := instance.QueryStats("proxy", direction); count != 0 {
			t.Fatalf("%s delta consumed twice: %d", direction, count)
		}
	}
}
