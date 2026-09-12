package profile

import (
	"encoding/base64"
	"net/url"
	"reflect"
	"strings"
	"testing"
)

func TestExportRoundTripSupportedProtocols(t *testing.T) {
	reality := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	vmessBody := `{"v":"2","ps":"VMess😀","add":"example.com","port":443,"id":"` + testUUID + `","aid":3,"scy":"auto","net":"ws","type":"none","host":"cdn.example.com","path":"/proxy","tls":"tls","sni":"example.com","alpn":"h2,http/1.1","fp":"chrome"}`
	cases := []string{
		"ss://" + base64.RawURLEncoding.EncodeToString([]byte("aes-128-gcm:p:@?#%😀")) + "@[2001:db8::1]:8388/?plugin=v2ray-plugin%3Btls%3Bhost%3Dexample.com#東京😀",
		"socks5://user:p%40ss@example.com:1080#SOCKS",
		"socks4a://user@[::1]:1080#IPv6",
		"https://user:p%3Ass@example.com:8443?sni=example.com&alpn=h2&fp=chrome#HTTPS",
		"http://example.com:80",
		"trojan://p%3A%40%3F%23%25@example.com?security=tls&type=grpc&serviceName=proxy&sni=example.com#Trojan",
		"anytls://secret@example.com?alpn=h2&fp=chrome#AnyTLS",
		"vless://" + testUUID + "@example.com?security=reality&pbk=" + reality + "&sid=ab12&flow=xtls-rprx-vision&packetEncoding=xudp#VLESS",
		"vless://" + testUUID + "@example.com?security=tls&type=ws&path=%2Fproxy&host=cdn.example.com&ed=2048&eh=Sec-WebSocket-Protocol#WS",
		"vmess://" + base64.StdEncoding.EncodeToString([]byte(vmessBody)),
		"hy2://u%3Ap%3A%40@example.com?obfs=salamander&obfs-password=secret&mport=443,5000:6000#HY2",
		"tuic://" + testUUID + ":secret@example.com?congestion_control=bbr&udp_relay_mode=quic&zero_rtt_handshake=1&allow_insecure=true#TUIC",
	}
	for _, uri := range cases {
		p, e := ParseURI(uri)
		if e != nil {
			t.Fatalf("fixture: %v", e)
		}
		p.ID = "local-only-id"
		share, e := ExportURI(p)
		if e != nil {
			t.Fatalf("%s: %v", p.Type, e)
		}
		if strings.Contains(share, p.ID) {
			t.Fatal("storage identity leaked")
		}
		round, e := ParseURI(share)
		if e != nil {
			t.Fatalf("export rejected: %s %v", p.Type, e)
		}
		p.ID = ""
		if !reflect.DeepEqual(round, p) {
			t.Errorf("%s roundtrip mismatch\nwant %#v\ngot %#v", p.Type, p, round)
		}
		again, e := ExportURI(round)
		if e != nil || again != share {
			t.Fatal("share output is not deterministic", p.Type, e)
		}
	}
}
func TestVMessExportTransports(t *testing.T) {
	for _, tr := range []*Transport{nil, {Type: "ws", Host: []string{"a.example", "b.example"}, Path: "/ws"}, {Type: "http", Host: []string{"a.example"}, Path: "/http"}, {Type: "httpupgrade", Path: "/upgrade"}, {Type: "grpc", ServiceName: "service"}} {
		p := Profile{Type: "vmess", Name: "VMess😀", Server: "example.com", Port: 443, VMess: &VMess{UUID: testUUID, Security: "auto"}, Transport: tr, TLS: &TLS{Enabled: true}}
		share, e := ExportURI(p)
		if e != nil {
			t.Fatal(e)
		}
		round, e := ParseURI(share)
		if e != nil || !reflect.DeepEqual(p, round) {
			t.Fatalf("transport %v roundtrip %v", tr, e)
		}
	}
}
func TestExportRejectsUnrepresentableFields(t *testing.T) {
	base := Profile{Type: "vless", Server: "example.com", Port: 443, VLESS: &VLESS{UUID: testUUID}, TLS: &TLS{Enabled: true}}
	cases := []struct {
		p     Profile
		field string
	}{}
	p := base
	p.TLS = &TLS{Enabled: true, Certificate: "certificate"}
	cases = append(cases, struct {
		p     Profile
		field string
	}{p, "tls.certificate"})
	p = base
	p.Transport = &Transport{Type: "ws", Headers: map[string][]string{"Authorization": {"Bearer synthetic"}}}
	cases = append(cases, struct {
		p     Profile
		field string
	}{p, "transport.headers"})
	p = base
	p.TLS = &TLS{Enabled: true, ALPN: []string{"a,b"}}
	cases = append(cases, struct {
		p     Profile
		field string
	}{p, "tls.alpn"})
	p = base
	p.Transport = &Transport{Type: "ws", Host: []string{"a,b"}}
	cases = append(cases, struct {
		p     Profile
		field string
	}{p, "transport.host"})
	vm := Profile{Type: "vmess", Server: "example.com", Port: 443, VMess: &VMess{UUID: testUUID, PacketEncoding: "xudp"}}
	cases = append(cases, struct {
		p     Profile
		field string
	}{vm, "vmess.packet_encoding"})
	vm.VMess = &VMess{UUID: testUUID}
	vm.TLS = &TLS{Enabled: true, Insecure: true}
	cases = append(cases, struct {
		p     Profile
		field string
	}{vm, "tls.insecure"})
	vm.TLS = nil
	vm.Transport = &Transport{Type: "ws", MaxEarlyData: 100}
	cases = append(cases, struct {
		p     Profile
		field string
	}{vm, "transport.early_data"})
	hy := Profile{Type: "hysteria2", Server: "example.com", Port: 443, Hysteria2: &Hysteria2{Password: "secret", UpMbps: 100}, TLS: &TLS{Enabled: true}}
	cases = append(cases, struct {
		p     Profile
		field string
	}{hy, "hysteria2.up_mbps"})
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	wg := Profile{Type: "wireguard", Server: "example.com", Port: 51820, WireGuard: &WireGuard{PrivateKey: key, PublicKey: key, Address: []string{"10.0.0.2/32"}}}
	cases = append(cases, struct {
		p     Profile
		field string
	}{wg, "wireguard"})
	for _, c := range cases {
		share, e := ExportURI(c.p)
		if e == nil || share != "" {
			t.Fatalf("lossy export admitted: %s", c.field)
		}
		typed, ok := e.(*Error)
		if !ok || typed.Field != c.field {
			t.Errorf("expected %s error, got %v", c.field, e)
		}
	}
}
func TestSSExportUsesSIP002Userinfo(t *testing.T) {
	p := Profile{Type: "shadowsocks", Server: "example.com", Port: 8388, Shadowsocks: &Shadowsocks{Method: "aes-128-gcm", Password: "secret", Plugin: "v2ray-plugin", PluginOptions: "tls;host=example.com"}}
	share, e := ExportURI(p)
	if e != nil {
		t.Fatal(e)
	}
	u, e := url.Parse(share)
	if e != nil {
		t.Fatal(e)
	}
	decoded, e := base64.RawURLEncoding.DecodeString(u.User.Username())
	if e != nil || string(decoded) != "aes-128-gcm:secret" || u.Query().Get("plugin") != "v2ray-plugin;tls;host=example.com" {
		t.Fatal("not SIP002")
	}
}
func FuzzExportURI(f *testing.F) {
	for _, s := range []string{"trojan://p@host", "vless://" + testUUID + "@host", "ss://YWVzLTEyOC1nY206cA@host:443", "anytls://p@host"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		if len(s) > 65536 {
			t.Skip()
		}
		p, e := ParseURI(s)
		if e != nil {
			return
		}
		share, e := ExportURI(p)
		if e != nil {
			return
		}
		round, e := ParseURI(share)
		if e != nil {
			t.Fatal("export cannot be parsed", e)
		}
		if e = Validate(round); e != nil {
			t.Fatal(e)
		}
		again, e := ExportURI(round)
		if e != nil || again != share {
			t.Fatal("unstable export")
		}
	})
}

// AEAD-2022 userinfo MUST NOT be Base64URL encoded (SIP002).
func TestSS2022ExportUsesPercentEncodedCredentials(t *testing.T) {
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	p := Profile{Type: "shadowsocks", Server: "example.com", Port: 8388, Shadowsocks: &Shadowsocks{Method: "2022-blake3-aes-256-gcm", Password: key}}
	share, e := ExportURI(p)
	if e != nil {
		t.Fatal(e)
	}
	u, e := url.Parse(share)
	if e != nil {
		t.Fatal(e)
	}
	pw, ok := u.User.Password()
	if !ok || u.User.Username() != p.Shadowsocks.Method || pw != key {
		t.Fatal("AEAD2022 userinfo was not percent encoded")
	}
	round, e := ParseURI(share)
	if e != nil || !reflect.DeepEqual(round, p) {
		t.Fatal("AEAD2022 roundtrip", e)
	}
}
