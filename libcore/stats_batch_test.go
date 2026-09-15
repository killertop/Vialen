package libcore

import (
	"encoding/binary"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBatchStatsRetainFinalBytesAndDrainUniqueTagsOnce(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer server.Close()
	b, err := NewSingBoxInstance(`{"outbounds":[{"type":"direct","tag":"proxy"}]}`, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	b.SetV2rayStats("proxy")
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	session := NewUrlTestSession()
	defer session.Cancel()
	if _, err := session.Run(b, server.URL, 1000); err != nil {
		t.Fatal(err)
	}
	if err := b.Close(); err != nil {
		t.Fatal(err)
	}
	result := b.QueryStatsBatch("proxy\nmissing\nproxy")
	if len(result) != 48 {
		t.Fatal(len(result))
	}
	for _, offset := range []int{0, 8} {
		first := binary.LittleEndian.Uint64(result[offset:])
		if first == 0 || first != binary.LittleEndian.Uint64(result[32+offset:]) {
			t.Fatal("lost or double-drained final counter")
		}
		if binary.LittleEndian.Uint64(result[16+offset:]) != 0 {
			t.Fatal("missing counter")
		}
	}
	for _, v := range b.QueryStatsBatch("proxy") {
		if v != 0 {
			t.Fatal("counter drained twice")
		}
	}
	if len(b.QueryStatsBatch("")) != 0 {
		t.Fatal("empty request")
	}
}
