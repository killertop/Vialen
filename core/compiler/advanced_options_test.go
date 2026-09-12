package compiler

import (
	"strings"
	"testing"
	"time"

	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
)

func TestExistingFormOptionsReachPinnedOptions(t *testing.T) {
	r := request()
	r.Profiles[0].UDPOverTCP = &profile.UDPOverTCP{Enabled: true, Version: 1}
	p := compile(t, r)
	if p.Options.Outbounds[0].Options.(*option.SOCKSOutboundOptions).UDPOverTCP.Version != 1 {
		t.Fatal("UoT option lost")
	}
	r.Profiles = []profile.Profile{{ID: "a", Type: "vmess", Server: "proxy.example", Port: 443, VMess: &profile.VMess{UUID: "00000000-0000-4000-8000-000000000001"}, Multiplex: &profile.Multiplex{Enabled: true, Protocol: "smux", MaxStreams: 8, Padding: true}, TLS: &profile.TLS{Enabled: true, ECH: &profile.ECH{Enabled: true, QueryServerName: "ech.example"}}}}
	p = compile(t, r)
	vm := p.Options.Outbounds[0].Options.(*option.VMessOutboundOptions)
	if vm.Multiplex.MaxStreams != 8 || !vm.Multiplex.Padding || vm.TLS.ECH.QueryServerName != "ech.example" {
		t.Fatal("multiplex/ECH options lost")
	}
	r.Profiles = []profile.Profile{{ID: "a", Type: "hysteria2", Server: "proxy.example", Port: 443, Hysteria2: &profile.Hysteria2{Password: "test", HopInterval: 10, HopIntervalMax: 20, BBRProfile: "conservative", DisableChromeParrot: true, Obfs: &profile.Obfs{Type: "gecko", Password: "test", MinPacketSize: 512, MaxPacketSize: 1200}}, TLS: &profile.TLS{Enabled: true}}}
	p = compile(t, r)
	hy := p.Options.Outbounds[0].Options.(*option.Hysteria2OutboundOptions)
	if time.Duration(hy.HopInterval) != 10*time.Second || hy.Obfs.GeckoOptions.MaxPacketSize != 1200 || !strings.Contains(p.Config, `"min_packet_size":512`) {
		t.Fatal("Hysteria form tuning lost")
	}
	r.Profiles = []profile.Profile{{ID: "a", Type: "tuic", Server: "proxy.example", Port: 443, TUIC: &profile.TUIC{UUID: "00000000-0000-4000-8000-000000000001", Password: "test"}, TLS: &profile.TLS{Enabled: true, DisableSNI: true}}}
	p = compile(t, r)
	if !p.Options.Outbounds[0].Options.(*option.TUICOutboundOptions).TLS.DisableSNI {
		t.Fatal("TUIC disable SNI lost")
	}
}
