package profile

import (
	"encoding/base64"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

const testUUID = "123e4567-e89b-12d3-a456-426614174000"

func TestSupportedShareURIs(t *testing.T) {
	key := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	vmessJSON := `{"v":"2","ps":"东京😀","add":"example.com","port":"443","id":"` + testUUID + `","aid":0,"scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/proxy","tls":"tls","sni":"example.com","alpn":"h2,http/1.1"}`
	for _, tc := range []struct {
		uri, kind string
		port      uint16
	}{
		{"ss://" + base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:secret")) + "@example.com:8388#香港😀", "shadowsocks", 8388},
		{"ss://aes-256-gcm:p%40ss@example.com:8388/?plugin=v2ray-plugin%3Btls%3Bhost%3Dexample.com", "shadowsocks", 8388},
		{"socks5://user:password@[2001:db8::1]:1080", "socks", 1080},
		{"socks4a://user@example.com:1080", "socks", 1080},
		{"http://user:password@example.com:8080", "http", 8080},
		{"https://user:password@example.com?sni=example.com", "http", 443},
		{"trojan://secret@example.com?type=ws&path=%2Fproxy&sni=example.com", "trojan", 443},
		{"anytls://secret@example.com?alpn=h2", "anytls", 443},
		{"vless://" + testUUID + "@example.com?security=reality&pbk=" + key + "&sid=01ab&flow=xtls-rprx-vision", "vless", 443},
		{"vmess://" + testUUID + "@example.com?security=tls&type=grpc&serviceName=proxy", "vmess", 443},
		{"vmess://" + base64.StdEncoding.EncodeToString([]byte(vmessJSON)), "vmess", 443},
		{"hysteria2://secret@example.com?obfs=salamander&obfs-password=hidden", "hysteria2", 443},
		{"hy2://user:password@example.com?mport=443,5000:6000", "hysteria2", 443},
		{"tuic://" + testUUID + ":password@example.com?congestion_control=bbr&udp_relay_mode=quic&allow_insecure=false", "tuic", 443},
	} {
		t.Run(tc.kind, func(t *testing.T) {
			p, e := ParseURI(tc.uri)
			if e != nil {
				t.Fatal(e)
			}
			if p.Type != tc.kind || p.Port != tc.port {
				t.Fatalf("unexpected profile %+v", p)
			}
			if e = Validate(p); e != nil {
				t.Fatal(e)
			}
			b, e := json.Marshal(p)
			if e != nil {
				t.Fatal(e)
			}
			var round Profile
			if e = json.Unmarshal(b, &round); e != nil || !reflect.DeepEqual(p, round) {
				t.Fatal("typed JSON roundtrip", e)
			}
			if p.ID != "" {
				t.Fatal("parser must not manufacture storage identity")
			}
		})
	}
}
func TestStrictBoundaries(t *testing.T) {
	for _, uri := range []string{
		"vless://not-a-uuid@example.com", "tuic://" + testUUID + "@example.com", "trojan://@example.com",
		"trojan://p@example.com:0", "trojan://p@example.com:65536", "trojan://p@example.com:+443", "trojan://p@example.com:",
		"trojan://p@example.com?sni=a&sni=b", "trojan://p@example.com?sni=a&%73ni=b", "trojan://p@example.com?unknown=1",
		"trojan://p@example.com?security=none", "trojan://p@example.com?insecure=maybe", "trojan://p@example.com?insecure=1&allowInsecure=1",
		"vless://" + testUUID + "@example.com?type=ws&ed=-1", "vless://" + testUUID + "@example.com?type=grpc&path=%2Fbad",
		"ss://" + base64.StdEncoding.EncodeToString([]byte("aes-128-gcm:p@host:443")),
		"vmess://" + base64.StdEncoding.EncodeToString([]byte("name = vmess,host,443,auto,"+testUUID)),
		"vmess://" + base64.StdEncoding.EncodeToString([]byte("auto:"+testUUID+"@host:443")) + "?remarks=old",
		"vmess://" + base64.StdEncoding.EncodeToString([]byte(`{'add':'host','port':443}`)),
		"http://example.com/subscription.yaml", "hysteria://host:443", "trojan://p@host\n", "trojan://p@exa_mple.com",
	} {
		if p, e := ParseURI(uri); e == nil {
			t.Fatalf("invalid URI admitted: %q => %+v", uri, p)
		}
	}
	for _, s := range []string{"aGV!sbG8=", "aGVsbG8===", "A", "AB==", "aG Vs"} {
		if _, e := DecodeBase64(s); e == nil {
			t.Errorf("invalid base64 admitted %q", s)
		}
	}
	for _, s := range []string{"aGVsbG8=", "aGVsbG8", "-_8=", "-_8"} {
		if _, e := DecodeBase64(s); e != nil {
			t.Errorf("valid base64 rejected %q: %v", s, e)
		}
	}
	if _, e := ParseURI(strings.Repeat("x", MaxURIBytes+1)); e == nil {
		t.Fatal("URI size limit")
	}
}
func TestValidationRejectsConflictingOrMissingOptions(t *testing.T) {
	p := Profile{Type: "trojan", Server: "example.com", Port: 443, Trojan: &Trojan{Password: "secret"}, TLS: &TLS{Enabled: true}}
	bad := p
	bad.VLESS = &VLESS{UUID: testUUID}
	if e := Validate(bad); e == nil {
		t.Fatal("multiple protocol options accepted")
	}
	bad = p
	bad.Trojan = nil
	if e := Validate(bad); e == nil {
		t.Fatal("missing options accepted")
	}
	bad = p
	bad.TLS = nil
	if e := Validate(bad); e == nil {
		t.Fatal("Trojan without TLS")
	}
	bad = p
	bad.Transport = &Transport{Type: "ws", Headers: map[string][]string{"X-Test": {"ok\r\ninjected"}}}
	if e := Validate(bad); e == nil {
		t.Fatal("header injection accepted")
	}
	bad = p
	bad.Transport = &Transport{Type: "ws", Host: []string{"a"}, Headers: map[string][]string{"host": {"b"}}}
	if e := Validate(bad); e == nil {
		t.Fatal("conflicting Host accepted")
	}
	bad = p
	bad.TLS = &TLS{Enabled: false, ALPN: []string{"h2"}}
	if e := Validate(bad); e == nil {
		t.Fatal("inactive TLS options accepted")
	}
	bad = p
	bad.TLS = &TLS{Enabled: true, Reality: &Reality{PublicKey: "bad"}}
	if e := Validate(bad); e == nil {
		t.Fatal("bad Reality key")
	}
	p.Transport = &Transport{Type: "ws", Path: "/proxy", Headers: map[string][]string{"Authorization": {"Bearer test"}}}
	if e := Validate(p); e != nil {
		t.Fatal(e)
	}
}
func TestWireGuardJSONAndRequiredFields(t *testing.T) {
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	p := Profile{Type: "wireguard", Server: "example.com", Port: 51820, WireGuard: &WireGuard{PrivateKey: key, PublicKey: key, Address: []string{"10.0.0.2/32", "fd00::2/128"}, AllowedIPs: []string{"0.0.0.0/0", "::/0"}, Reserved: []uint32{0, 128, 255}}}
	if e := Validate(p); e != nil {
		t.Fatal(e)
	}
	b, _ := json.Marshal(p)
	if !strings.Contains(string(b), `"reserved":[0,128,255]`) {
		t.Fatal("reserved must be JSON numbers", string(b))
	}
	p.WireGuard.Reserved[2] = 256
	if e := Validate(p); e == nil {
		t.Fatal("reserved overflow")
	}
	p.WireGuard.Reserved = nil
	p.WireGuard.Address = nil
	if e := Validate(p); e == nil {
		t.Fatal("missing interface address")
	}
}
func TestNamesIDNAAndPrivateErrors(t *testing.T) {
	p, e := ParseURI("trojan://super-secret@faß.de#東京😀")
	if e != nil {
		t.Fatal(e)
	}
	if p.Server != "xn--fa-hia.de" || p.Name != "東京😀" {
		t.Fatalf("modern IDNA/unicode got %+v", p)
	}
	_, e = ParseURI("trojan://super-secret@host?insecure=wrong")
	if e == nil || strings.Contains(e.Error(), "super-secret") || strings.Contains(e.Error(), "wrong") {
		t.Fatal("error leaked user input", e)
	}
	if got := (Profile{Server: "::1", Port: 443}).DisplayName(); got != "[::1]:443" {
		t.Fatal(got)
	}
}
func FuzzProfileURI(f *testing.F) {
	for _, s := range []string{"", "trojan://p@example.com", "ss://YWVzLTEyOC1nY206cA@host:443", "vless://" + testUUID + "@host", "vmess://e30=", "hy2://p@host", "anytls://p@host"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		if len(s) > 65536 {
			t.Skip()
		}
		p, e := ParseURI(s)
		if e == nil {
			if e = Validate(p); e != nil {
				t.Fatal("parser returned invalid profile", e)
			}
			data, e := json.Marshal(p)
			if e != nil {
				t.Fatal(e)
			}
			var decoded Profile
			if e = json.Unmarshal(data, &decoded); e != nil {
				t.Fatal(e)
			}
			if e = Validate(decoded); e != nil {
				t.Fatal("JSON roundtrip invalid", e)
			}
		}
	})
}
func BenchmarkParseURI(b *testing.B) {
	for _, s := range []string{"ss://YWVzLTEyOC1nY206cGFzc3dvcmQ@example.com:443", "vless://" + testUUID + "@example.com?security=tls&type=ws&path=%2Fproxy"} {
		b.Run(strings.Split(s, ":")[0], func(b *testing.B) {
			b.ReportAllocs()
			for b.Loop() {
				_, _ = ParseURI(s)
			}
		})
	}
}

func TestJSONShareRejectsAmbiguity(t *testing.T) {
	for _, body := range []string{
		`{"add":"a","add":"b","port":443,"id":"` + testUUID + `"}`,
		`{"add":"a","port":443.5,"id":"` + testUUID + `"}`,
		`{"add":"a","port":443,"id":"` + testUUID + `","ps":{}}`,
		`{"add":"a","port":443,"id":"` + testUUID + `","unexpected":"value"}`,
	} {
		if _, e := ParseURI("vmess://" + base64.StdEncoding.EncodeToString([]byte(body))); e == nil {
			t.Fatal("ambiguous JSON accepted")
		}
	}
	for _, uri := range []string{"https://host?security=none", "trojan://p@host?flow=xtls-rprx-vision", "trojan://p@host?encryption=auto"} {
		if _, e := ParseURI(uri); e == nil {
			t.Fatal("inapplicable option accepted")
		}
	}
	p, e := ParseURI("SOCKS4://u@host:1080")
	if e != nil || p.Socks.Version != "4" {
		t.Fatal("scheme case", e)
	}
}
