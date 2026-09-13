package importer

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestSingboxRejectsUnpreservedTLSConstraints(t *testing.T) {
	for _, key := range []string{"certificate_public_key_sha256", "min_version", "max_version", "cipher_suites", "certificate_path"} {
		t.Run(key, func(t *testing.T) {
			node := map[string]any{"type": "trojan", "server": "example.com", "server_port": 443, "password": "synthetic-secret", "tls": map[string]any{"enabled": true, key: "synthetic-constraint"}}
			b, err := json.Marshal([]any{node})
			if err != nil {
				t.Fatal(err)
			}
			r := mustParse(t, "singbox", string(b))
			if len(r.Profiles) != 0 || len(r.Issues) != 1 || r.Issues[0].Code != "UNSUPPORTED_TLS_FIELD" {
				t.Fatalf("constraint accepted: %+v", r)
			}
			if strings.Contains(r.Issues[0].Message, "synthetic") {
				t.Fatal("secret leaked in error")
			}
		})
	}
}

func TestSingboxWireguardDetourRejected(t *testing.T) {
	for _, detour := range []any{"upstream", 123} {
		endpoint := map[string]any{"type": "wireguard", "detour": detour, "address": []string{"10.0.0.2/32"}, "private_key": wgKey, "peers": []any{map[string]any{"address": "example.com", "port": 51820, "public_key": wgKey}}}
		b, err := json.Marshal(map[string]any{"endpoints": []any{endpoint}})
		if err != nil {
			t.Fatal(err)
		}
		r := mustParse(t, "singbox", string(b))
		if len(r.Profiles) != 0 || len(r.Issues) != 1 {
			t.Fatalf("dependency accepted: %+v", r)
		}
		if detour == "upstream" && r.Issues[0].Code != "DEPENDENT_NODE" {
			t.Fatalf("wrong issue: %+v", r)
		}
	}
}
