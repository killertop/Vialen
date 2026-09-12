package importer

import "testing"

func TestShadowTLSImports(t *testing.T) {
	for format, text := range map[string]string{
		"clash":   `proxies: [{type: shadowtls, name: transport, server: example.com, port: 443, version: 3, password: secret, sni: cover.example.com}]`,
		"singbox": `{"outbounds":[{"type":"shadowtls","tag":"transport","server":"example.com","server_port":443,"version":3,"password":"secret","tls":{"enabled":true,"server_name":"cover.example.com"}}]}`,
	} {
		r := mustParse(t, format, text)
		if len(r.Profiles) != 1 || len(r.Issues) != 0 {
			t.Fatalf("%s: %+v", format, r)
		}
		p := r.Profiles[0]
		if p.ShadowTLS == nil || p.ShadowTLS.Version != 3 || p.ShadowTLS.Password != "secret" || p.TLS.ServerName != "cover.example.com" {
			t.Fatal(p)
		}
	}
}

func TestSingboxAdvancedProfileImport(t *testing.T) {
	r := mustParse(t, "singbox", `{"outbounds":[{"type":"hysteria2","server":"example.com","server_port":443,"password":"secret","hop_interval":"10s","hop_interval_max":"20s","bbr_profile":"aggressive","disable_chrome_parrot":true,"obfs":{"type":"gecko","password":"secret","min_packet_size":512,"max_packet_size":1200},"tls":{"enabled":true,"disable_sni":true,"ech":{"enabled":true,"config":["config"]}}},{"type":"shadowsocks","server":"example.com","server_port":443,"method":"aes-128-gcm","password":"secret","udp_over_tcp":{"enabled":true,"version":1},"multiplex":{"enabled":true,"protocol":"smux","max_streams":8}}]}`)
	if len(r.Profiles) != 2 || len(r.Issues) != 0 {
		t.Fatalf("%+v", r)
	}
	h := r.Profiles[0]
	if h.Hysteria2.HopInterval != 10 || h.Hysteria2.Obfs.MinPacketSize != 512 || h.TLS.ECH == nil || !h.TLS.DisableSNI {
		t.Fatal(h)
	}
	s := r.Profiles[1]
	if s.UDPOverTCP.Version != 1 || s.Multiplex.MaxStreams != 8 {
		t.Fatal(s)
	}
}
