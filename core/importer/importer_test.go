package importer

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/killertop/Vialen/core/profile"
	"strings"
	"testing"
)

const uuid = "00000000-0000-0000-0000-000000000001"

var wgKey = base64.StdEncoding.EncodeToString(make([]byte, 32))

func mustParse(t *testing.T, format, text string) Result {
	t.Helper()
	r, e := Parse(Request{Text: text, Format: format, FileName: "sample.conf"})
	if e != nil {
		t.Fatal(e)
	}
	return r
}
func TestLinksPartialIsVisible(t *testing.T) {
	r := mustParse(t, "links", "# comment\nsocks5://user:pass@example.com:1080#sample\nnot-a-uri\nsn://socks:AA==")
	if len(r.Profiles) != 1 || len(r.Issues) != 2 || r.Issues[0].Index != 2 || r.Issues[1].Code != "UNSUPPORTED_SCHEME" {
		t.Fatalf("%+v", r)
	}
	if r.Profiles[0].Name != "sample" || r.Profiles[0].Socks.Username != "user" {
		t.Fatal(r.Profiles[0])
	}
}
func TestBase64Standard(t *testing.T) {
	uri := "socks5://example.com:1080"
	encoded := base64.StdEncoding.EncodeToString([]byte(uri))
	for _, format := range []string{"auto", "base64"} {
		r := mustParse(t, format, encoded[:4]+"\n"+encoded[4:])
		if len(r.Profiles) != 1 || r.Format != "base64" {
			t.Fatalf("%+v", r)
		}
	}
	if _, e := Parse(Request{Format: "base64", Text: "not base64?!"}); e == nil {
		t.Fatal("invalid encoding accepted")
	}
}
func TestClashProtocols(t *testing.T) {
	cases := []struct{ kind, options, want string }{{"socks5", "username: user, password: pass", "socks"}, {"http", "username: user, password: pass, tls: true", "http"}, {"ss", "cipher: aes-128-gcm, password: pass", "shadowsocks"}, {"vmess", "uuid: " + uuid + ", cipher: auto, alterId: 0", "vmess"}, {"vless", "uuid: " + uuid, "vless"}, {"trojan", "password: pass", "trojan"}, {"hysteria2", "password: pass, up: 20 Mbps, down: 30, obfs: salamander, obfs-password: secret", "hysteria2"}, {"tuic", "uuid: " + uuid + ", password: pass, congestion-controller: bbr", "tuic"}, {"anytls", "password: pass", "anytls"}, {"wireguard", "private-key: " + wgKey + ", public-key: " + wgKey + ", ip: 10.0.0.2, allowed-ips: [0.0.0.0/0], reserved: [0, 1, 255]", "wireguard"}}
	for _, c := range cases {
		t.Run(c.kind, func(t *testing.T) {
			r := mustParse(t, "clash", "proxies: [{type: "+c.kind+", name: example, server: example.com, port: 443, "+c.options+"}]")
			if len(r.Profiles) != 1 || len(r.Issues) != 0 || r.Profiles[0].Type != c.want {
				t.Fatalf("%+v", r)
			}
			if e := profile.Validate(r.Profiles[0]); e != nil {
				t.Fatal(e)
			}
		})
	}
}
func TestClashMergeAndTransport(t *testing.T) {
	text := "base: &base {server: example.com, port: 443}\nproxies:\n - <<: *base\n   type: vless\n   uuid: " + uuid + "\n   tls: true\n   network: ws\n   ws-opts: {path: /proxy, headers: {Host: ws.example.com, Authorization: bearer-synthetic}, max-early-data: 2048}\n"
	r := mustParse(t, "auto", text)
	if len(r.Profiles) != 1 || len(r.Issues) != 0 {
		t.Fatalf("%+v", r)
	}
	tr := r.Profiles[0].Transport
	if tr.Type != "ws" || tr.Path != "/proxy" || tr.Headers["Authorization"][0] != "bearer-synthetic" {
		t.Fatalf("%+v", tr)
	}
}
func TestStrictFormatsNeverRetry(t *testing.T) {
	for _, text := range []string{`{outbounds:[]}`, `{"outbounds":[],}`, `{"outbounds":[],"outbounds":[]}`, `{"outbounds":[]} garbage`, `{"outbounds":[/*x*/]}`} {
		if _, e := Parse(Request{Text: text, Format: "auto"}); e == nil {
			t.Fatalf("accepted nonstandard JSON %s", text)
		}
	}
	for _, text := range []string{"proxies: [{type: http, server: x, port: 443, port: 80}]", "proxies: []\n---\nproxies: []", "proxies: &x [*x]"} {
		if _, e := Parse(Request{Text: text, Format: "clash"}); e == nil {
			t.Fatal("accepted malformed/recursive YAML")
		}
	}
	r := mustParse(t, "clash", "proxies: [{type: http, server: x, port: '443'}, {type: http, server: x, port: 443, tls: yes}]")
	if len(r.Profiles) != 0 || len(r.Issues) != 2 {
		t.Fatalf("coerced standard scalar types: %+v", r)
	}
}
func TestSingboxNodesNotFullConfig(t *testing.T) {
	text := `{"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"node","server":"example.com","server_port":443,"uuid":"` + uuid + `","tls":{"enabled":true,"server_name":"tls.example"},"transport":{"type":"ws","path":"/ws","headers":{"Host":"ws.example","Authorization":"synthetic"}}},{"type":"socks","server":"example.com","server_port":1080,"detour":"other"}]}`
	r := mustParse(t, "auto", text)
	if len(r.Profiles) != 1 || len(r.Issues) != 2 || r.Issues[0].Code != "NON_PROXY_ENTRY" || r.Issues[1].Code != "DEPENDENT_NODE" {
		t.Fatalf("%+v", r)
	}
	if r.Profiles[0].Transport.Headers["Authorization"][0] != "synthetic" {
		t.Fatal(r.Profiles[0])
	}
	if _, e := Parse(Request{Format: "singbox", Text: `{"dns":{},"route":{}}`}); e == nil {
		t.Fatal("whole config treated as profile")
	}
}
func TestSingboxWireguardEndpointPeers(t *testing.T) {
	v := map[string]any{"endpoints": []any{map[string]any{"type": "wireguard", "tag": "wg", "address": []string{"10.0.0.2/32"}, "private_key": wgKey, "peers": []any{map[string]any{"address": "first.example", "port": 51820, "public_key": wgKey, "allowed_ips": "0.0.0.0/0"}, map[string]any{"address": "second.example", "port": 51821, "public_key": wgKey, "reserved": []int{1, 2, 3}}}}}}
	b, _ := json.Marshal(v)
	r := mustParse(t, "singbox", string(b))
	if len(r.Profiles) != 2 || len(r.Issues) != 0 || r.Profiles[1].WireGuard.Reserved[2] != 3 {
		t.Fatalf("%+v", r)
	}
}
func TestWireguardStandardPeersAndOptionalMTU(t *testing.T) {
	text := "[Interface]\nPrivateKey = " + wgKey + "\nAddress = 10.0.0.2/32, fd00::2/128\nDNS = 1.1.1.1\n[Peer]\nPublicKey = " + wgKey + "\nEndpoint = example.com:51820\nAllowedIPs = 0.0.0.0/0, ::/0\n[Peer]\nPublicKey = " + wgKey + "\nEndpoint = [2001:db8::1]:51821\n"
	r := mustParse(t, "auto", text)
	if len(r.Profiles) != 2 || len(r.Issues) != 1 || r.Issues[0].Code != "INTERFACE_SETTING_OMITTED" || r.Profiles[0].WireGuard.MTU != 0 {
		t.Fatalf("%+v", r)
	}
	if r.Profiles[1].Server != "2001:db8::1" {
		t.Fatal(r.Profiles[1])
	}
}
func TestResourceLimits(t *testing.T) {
	for _, req := range []Request{{Text: strings.Repeat("x", MaxInputBytes+1)}, {Format: "links", Text: strings.Repeat("socks5://x:1080\n", MaxProfiles+1)}, {Format: "singbox", Text: strings.Repeat("[", 65) + "0" + strings.Repeat("]", 65)}, {Format: "clash", Text: "proxies: " + strings.Repeat("[", 66) + "0" + strings.Repeat("]", 66)}} {
		if _, e := Parse(req); e == nil {
			t.Fatal("limit accepted")
		}
	}
	yaml := "a0: &a0 [x,x,x,x,x,x,x,x,x,x]\n"
	for i := 1; i < 7; i++ {
		yaml += fmt.Sprintf("a%d: &a%d [*a%d,*a%d,*a%d,*a%d,*a%d,*a%d,*a%d,*a%d,*a%d,*a%d]\n", i, i, i-1, i-1, i-1, i-1, i-1, i-1, i-1, i-1, i-1, i-1)
	}
	yaml += "proxies: *a6"
	if _, e := Parse(Request{Format: "clash", Text: yaml}); e == nil {
		t.Fatal("alias expansion accepted")
	}
}
func FuzzParse(f *testing.F) {
	for _, s := range []string{`{"outbounds":[]}`, "proxies: []", "socks5://example.com:1080", "[Interface]\nPrivateKey=x", `{"outbounds":[{"type":"socks","server":"x","server_port":1080}]}`} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		if len(s) > 65536 {
			t.Skip()
		}
		r, e := Parse(Request{Text: s})
		if e == nil {
			if len(r.Profiles) > MaxProfiles {
				t.Fatal("limit")
			}
			for _, p := range r.Profiles {
				if e := profile.Validate(p); e != nil {
					t.Fatal("returned invalid profile", e)
				}
			}
		}
	})
}

func TestJSONUnicodeIsNotReplaced(t *testing.T) {
	for _, text := range []string{`{"outbounds":[{"type":"http","server":"example.com","server_port":80,"password":"\ud800"}]}`, `{"outbounds":[{"type":"http","server":"example.com","server_port":80,"password":"\udc00"}]}`} {
		if _, e := Parse(Request{Format: "singbox", Text: text}); e == nil {
			t.Fatal("surrogate silently replaced")
		}
	}
	r := mustParse(t, "singbox", `{"outbounds":[{"type":"http","server":"example.com","server_port":80,"password":"\ud83d\ude00"}]}`)
	if len(r.Profiles) != 1 || r.Profiles[0].HTTP.Password != "😀" {
		t.Fatalf("%+v", r)
	}
}

func TestIssueSeverityAndRefreshGate(t *testing.T) {
	warnings := mustParse(t, "singbox", `{"outbounds":[{"type":"direct"},{"type":"socks","server":"127.0.0.1","server_port":1080}]}`)
	if warnings.HasErrors() || len(warnings.Profiles) != 1 || len(warnings.Issues) != 1 || warnings.Issues[0].Severity != SeverityWarning {
		t.Fatalf("%+v", warnings)
	}
	partial := mustParse(t, "singbox", `{"outbounds":[{"type":"socks","server":"127.0.0.1","server_port":1080},{"type":"socks","server":"127.0.0.1","server_port":"bad"}]}`)
	if !partial.HasErrors() || len(partial.Profiles) != 1 || partial.Issues[0].Severity != SeverityError {
		t.Fatalf("%+v", partial)
	}
	if !(Result{Issues: []Issue{{Code: "unknown"}}}).HasErrors() {
		t.Fatal("unknown severity must fail closed")
	}
	b, e := json.Marshal(partial)
	if e != nil || !strings.Contains(string(b), `"severity":"error"`) {
		t.Fatal(string(b), e)
	}
}
