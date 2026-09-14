package importer

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestClashDialerDependency(t *testing.T) {
	for _, tc := range []struct {
		value   any
		present bool
		code    string
	}{
		{nil, false, ""}, {nil, true, "INVALID_FIELD"}, {"", true, ""}, {"upstream-private", true, "DEPENDENT_NODE"},
		{" ", true, "INVALID_FIELD"}, {123, true, "INVALID_FIELD"}, {false, true, "INVALID_FIELD"},
	} {
		node := map[string]any{"type": "socks5", "name": "test", "server": "example.com", "port": 1080}
		if tc.present {
			node["dialer-proxy"] = tc.value
		}
		data, _ := json.Marshal(map[string]any{"proxies": []any{node}})
		r := mustParse(t, "clash", string(data))
		if tc.code == "" {
			if len(r.Profiles) != 1 {
				t.Fatalf("ordinary node rejected: %+v", r)
			}
			continue
		}
		if len(r.Profiles) != 0 || len(r.Issues) != 1 || r.Issues[0].Code != tc.code {
			t.Fatalf("dependency not rejected as %s: %+v", tc.code, r)
		}
		if strings.Contains(r.Issues[0].Message, "upstream-private") {
			t.Fatal("dependency value leaked")
		}
	}
}

func TestClashConnectionConstraintsAreNotSilentlyDiscarded(t *testing.T) {
	for _, key := range []string{"interface-name", "fingerprint", "certificate", "private-key", "certificate-path", "private-key-path", "routing-mark"} {
		node := map[string]any{"type": "socks5", "name": "test", "server": "example.com", "port": 1080, key: "synthetic-private"}
		if key == "routing-mark" {
			node[key] = 10
		}
		data, _ := json.Marshal(map[string]any{"proxies": []any{node}})
		r := mustParse(t, "clash", string(data))
		if len(r.Profiles) != 0 || len(r.Issues) != 1 || r.Issues[0].Code != "UNSUPPORTED_SECURITY_FIELD" {
			t.Fatalf("constraint %s silently lost: %+v", key, r)
		}
		if strings.Contains(r.Issues[0].Message, "synthetic-private") {
			t.Fatal("constraint value leaked")
		}
	}
	r := mustParse(t, "clash", `proxies: [{type: socks5, server: example.com, port: 1080, vendor-label: harmless}]`)
	if len(r.Profiles) != 1 || len(r.Issues) != 0 {
		t.Fatal("unknown annotation rejected")
	}
}

func TestSingBoxConnectionBindingsCannotDisappear(t *testing.T) {
	for _, key := range []string{"bind_interface", "inet4_bind_address", "inet6_bind_address", "routing_mark"} {
		node := map[string]any{"type": "socks", "server": "example.com", "server_port": 1080, key: "synthetic-private"}
		if key == "routing_mark" {
			node[key] = 10
		}
		data, _ := json.Marshal(map[string]any{"outbounds": []any{node}})
		r := mustParse(t, "singbox", string(data))
		if len(r.Profiles) != 0 || len(r.Issues) != 1 || r.Issues[0].Code != "UNSUPPORTED_SECURITY_FIELD" {
			t.Fatalf("binding silently lost: %+v", r)
		}
	}
}
