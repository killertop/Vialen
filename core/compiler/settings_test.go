package compiler

import (
	"github.com/sagernet/sing-box/option"
	"testing"
)

func TestSettingsMTUBoundaries(t *testing.T) {
	for _, mtu := range []uint32{1000, 1279, 1280, 1500, 9000, 65535, 65536} {
		r := request()
		r.Platform.MTU = mtu
		p, err := Compile(r)
		valid := mtu >= 1280 && mtu <= 65535
		if (err == nil) != valid {
			t.Fatalf("MTU %d: %v", mtu, err)
		}
		if valid && p.Options.Inbounds[0].Options.(*option.TunInboundOptions).MTU != mtu {
			t.Fatal("TUN MTU changed")
		}
	}
}

func TestResolveAndFakeDNSCombinationsPreserveRoutingOrder(t *testing.T) {
	for _, resolve := range []bool{false, true} {
		for _, fake := range []bool{false, true} {
			r := request()
			r.Policy.ResolveDestination = resolve
			r.Policy.DNS.FakeIP = fake
			r.Policy.Sniff = true
			p := compile(t, r)
			rules := p.Options.Route.Rules
			index := 0
			if resolve {
				if rules[0].DefaultOptions.Action != "resolve" {
					t.Fatal("resolve order changed")
				}
				index++
			}
			if rules[index].DefaultOptions.Action != "sniff" || rules[index+1].DefaultOptions.Action != "hijack-dns" {
				t.Fatal("sniff / DNS interception order changed")
			}
			found := false
			for _, server := range p.Options.DNS.Servers {
				if server.Type == "fakeip" {
					found = true
				}
			}
			if found != fake {
				t.Fatal("FakeDNS selection changed")
			}
		}
	}
}
