package profile

import "testing"

func TestAdvancedOptionsAreTypedAndValidated(t *testing.T) {
	p := Profile{Type: "shadowsocks", Server: "example.com", Port: 443, Shadowsocks: &Shadowsocks{Method: "aes-128-gcm", Password: "secret"}, UDPOverTCP: &UDPOverTCP{Enabled: true, Version: 2}, Multiplex: &Multiplex{Enabled: true, Protocol: "smux", MaxStreams: 8}}
	if e := Validate(p); e != nil {
		t.Fatal(e)
	}
	if _, e := ExportURI(p); e == nil {
		t.Fatal("advanced options silently dropped")
	}
	p.UDPOverTCP.Version = 3
	if Validate(p) == nil {
		t.Fatal("invalid UoT version")
	}
	p.UDPOverTCP.Version = 2
	p.Multiplex.Protocol = "invalid"
	if Validate(p) == nil {
		t.Fatal("invalid multiplex")
	}
	h := Profile{Type: "hysteria2", Server: "example.com", Port: 443, TLS: &TLS{Enabled: true}, Hysteria2: &Hysteria2{Password: "secret", HopInterval: 10, HopIntervalMax: 20, BBRProfile: "aggressive", DisableChromeParrot: true, Obfs: &Obfs{Type: "gecko", Password: "secret", MinPacketSize: 512, MaxPacketSize: 1200}}}
	if e := Validate(h); e != nil {
		t.Fatal(e)
	}
	h.Hysteria2.HopIntervalMax = 5
	if Validate(h) == nil {
		t.Fatal("reversed duration range")
	}
}

func TestSIP003PluginAlias(t *testing.T) {
	p, e := ParseURI("ss://YWVzLTEyOC1nY206cGFzcw@example.com:443/?plugin=simple-obfs%3Bobfs%3Dhttp")
	if e != nil || p.Shadowsocks.Plugin != "obfs-local" {
		t.Fatalf("%+v %v", p, e)
	}
}
