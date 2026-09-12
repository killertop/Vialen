package importer

import (
	"encoding/json"
	"github.com/killertop/Vialen/core/profile"
	"strings"
	"testing"
)

func TestNativeProfileDocument(t *testing.T) {
	p, err := profile.ParseURI("vless://00000000-0000-0000-0000-000000000001@example.com:443?security=tls&type=ws#node")
	if err != nil {
		t.Fatal(err)
	}
	p.Transport.Headers = map[string][]string{"x-custom": {"a", "b"}}
	data, _ := json.Marshal(map[string]any{"profiles": []profile.Profile{p}})
	got, err := Parse(Request{Text: string(data)})
	if err != nil || got.HasErrors() || len(got.Profiles) != 1 {
		t.Fatalf("import failed: %v %+v", err, got)
	}
	if got.Profiles[0].Transport.Headers["x-custom"][1] != "b" {
		t.Fatal("advanced field lost")
	}
	for _, input := range []string{
		`{"profiles":[{"type":"socks","server":"localhost","port":1080,"socks":{"version":"5"},"unknown":true}]}`,
		`{"profiles":[],"outbounds":[]}`,
		`{"profiles":null}`,
		strings.Replace(string(data), `"port":443`, `"port":65536`, 1),
	} {
		got, err := Parse(Request{Text: input})
		if err == nil && !got.HasErrors() {
			t.Fatal("invalid native document accepted")
		}
	}
}
