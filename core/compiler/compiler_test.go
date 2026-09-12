package compiler

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
	SJ "github.com/sagernet/sing/common/json"
	"reflect"
	"strings"
	"sync"
	"testing"
)

func request() Request {
	return Request{Profiles: []profile.Profile{{ID: "a", Type: "socks", Server: "proxy.example", Port: 1080, Socks: &profile.Socks{Version: "5"}}}, SelectedID: "a", Purpose: Run, Policy: Policy{DNS: DNSPolicy{Direct: DNSServer{Type: "udp", Server: "1.1.1.1"}, Remote: DNSServer{Type: "https", Server: "dns.example"}, RouteDomains: true}}, Platform: Platform{VPN: true, TUNAddresses: []string{"172.19.0.1/28", "fdfe:dcba:9876::1/126"}, SupportsUIDRules: true}}
}
func compile(t *testing.T, r Request) Plan {
	t.Helper()
	p, e := Compile(r)
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func TestPinnedOptionsMarshalWithoutRuntime(t *testing.T) {
	r := request()
	p := compile(t, r)
	if _, ok := p.Options.Outbounds[0].Options.(*option.SOCKSOutboundOptions); !ok {
		t.Fatal("outbound must use the pinned option type")
	}
	raw, e := SJ.MarshalContext(context.Background(), &p.Options)
	if e != nil || string(raw) != p.Config {
		t.Fatal("plan config does not match typed options")
	}
	var config map[string]any
	if e = json.Unmarshal(raw, &config); e != nil {
		t.Fatal(e)
	}
	if config["route"].(map[string]any)["final"] != p.Metadata.SelectedTag {
		t.Fatal("missing selected route")
	}
	for _, forbidden := range []string{"domain_strategy", "endpoint_independent_nat", "+rules", "rules+"} {
		if strings.Contains(p.Config, forbidden) {
			t.Fatal("obsolete output field", forbidden)
		}
	}
	if len(p.Metadata.Bindings) != 1 || p.Metadata.Bindings[0].ProfileID != "a" {
		t.Fatal("missing explicit binding")
	}
}
func TestAllSupportedProtocolsUseTypedOptions(t *testing.T) {
	uuid := "00000000-0000-4000-8000-000000000001"
	tls := &profile.TLS{Enabled: true, ServerName: "tls.example"}
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	cases := []profile.Profile{{Type: "socks", Socks: &profile.Socks{Version: "5"}}, {Type: "http", HTTP: &profile.HTTP{Username: "user"}, TLS: tls}, {Type: "shadowsocks", Shadowsocks: &profile.Shadowsocks{Method: "aes-128-gcm", Password: "secret"}}, {Type: "vmess", VMess: &profile.VMess{UUID: uuid, PacketEncoding: "xudp"}}, {Type: "vless", VLESS: &profile.VLESS{UUID: uuid}, TLS: tls}, {Type: "trojan", Trojan: &profile.Trojan{Password: "secret"}, TLS: tls}, {Type: "hysteria2", Hysteria2: &profile.Hysteria2{Password: "secret"}, TLS: tls}, {Type: "tuic", TUIC: &profile.TUIC{UUID: uuid, Password: "secret"}, TLS: tls}, {Type: "anytls", AnyTLS: &profile.AnyTLS{Password: "secret"}, TLS: tls}, {Type: "wireguard", WireGuard: &profile.WireGuard{PrivateKey: key, PublicKey: key, Address: []string{"10.0.0.2/32"}, Reserved: []uint32{1, 2, 255}}}}
	for _, v := range cases {
		t.Run(v.Type, func(t *testing.T) {
			v.ID = "profile"
			v.Server = "127.0.0.1"
			v.Port = 443
			r := request()
			r.Profiles = []profile.Profile{v}
			r.SelectedID = v.ID
			p := compile(t, r)
			if v.Type == "wireguard" {
				if len(p.Options.Endpoints) != 1 || p.Options.Endpoints[0].Tag != p.Metadata.SelectorCandidates[0].Tag {
					t.Fatal("WireGuard must be an endpoint")
				}
				if _, ok := p.Options.Endpoints[0].Options.(*option.WireGuardEndpointOptions); !ok {
					t.Fatal("wrong endpoint type")
				}
			} else if p.Options.Outbounds[0].Type != v.Type {
				t.Fatal("wrong protocol")
			}
		})
	}
}
func TestChainClosureOrderingAndReuse(t *testing.T) {
	r := request()
	b := r.Profiles[0]
	b.ID = "b"
	r.Profiles = append(r.Profiles, b)
	r.Chains = []Chain{{ID: "nested", Hops: []string{"a", "b"}}, {ID: "chain", Hops: []string{"nested", "a"}}}
	r.SelectedID = "chain"
	r.Policy.Rules = []Rule{{ID: "again", Match: Match{Domains: []string{"example.com"}}, Action: "route", Target: &Target{Kind: "reference", ID: "chain"}}}
	p := compile(t, r)
	if len(p.Metadata.Bindings) != 3 {
		t.Fatal("selected chain must be reused")
	}
	tags := map[string]bool{"direct": true}
	for i, b := range p.Metadata.Bindings {
		tags[b.Tag] = true
		if b.Hop != i || b.ProfileID != []string{"a", "b", "a"}[i] {
			t.Fatal("hop order changed")
		}
	}
	for _, o := range p.Options.Outbounds {
		if s, ok := o.Options.(*option.SOCKSOutboundOptions); ok && s.Detour != "" && !tags[s.Detour] {
			t.Fatal("dangling detour")
		}
	}
	for _, hops := range [][]string{{"missing"}, {"chain"}, {}} {
		r.Chains = []Chain{{ID: "chain", Hops: hops}}
		if _, e := Compile(r); e == nil {
			t.Fatalf("accepted invalid chain %v", hops)
		}
	}
}
func TestDNSProjectionPreservesRuleOrder(t *testing.T) {
	r := request()
	r.Policy.DNS.FakeIP = true
	r.Policy.Rules = []Rule{{ID: "direct", Match: Match{Domains: []string{"direct.example"}}, Action: "route", Target: &Target{Kind: "direct"}}, {ID: "connection", Match: Match{Domains: []string{"limited.example"}, Ports: []uint16{443}}, Action: "route", Target: &Target{Kind: "selected"}}, {ID: "reject", Match: Match{Domains: []string{"limited.example"}}, Action: "reject"}}
	p := compile(t, r)
	if len(p.Metadata.Diagnostics) != 2 {
		t.Fatal("unsafe and subsequent rule must report skipped DNS projection")
	}
	for _, rule := range p.Options.DNS.Rules {
		if rule.DefaultOptions.Action == "reject" {
			t.Fatal("later reject must not shadow an earlier connection-dependent rule")
		}
	}
	if p.Options.DNS.Rules[0].DefaultOptions.RouteOptions.Server != "dns-direct" {
		t.Fatal("direct DNS rule lost precedence")
	}
	for _, server := range p.Options.DNS.Servers {
		if server.Tag == "dns-direct" {
			if server.Options.(*option.RemoteDNSServerOptions).Detour != "" {
				t.Fatal("bootstrap DNS must stay direct")
			}
		}
	}
}
func TestExplicitANDForDomainAndIP(t *testing.T) {
	r := request()
	r.Policy.Rules = []Rule{{ID: "and", Match: Match{Domains: []string{"example.com"}, IPCIDRs: []string{"203.0.113.0/24"}}, Action: "reject"}}
	p := compile(t, r)
	last := p.Options.Route.Rules[len(p.Options.Route.Rules)-1]
	if last.Type != "logical" || last.LogicalOptions.Mode != "and" || len(last.LogicalOptions.Rules) != 2 {
		t.Fatal("domain and IP constraints must both hold")
	}
}
func TestProbeDoesNotCreateListeners(t *testing.T) {
	r := request()
	r.Purpose = Probe
	r.Platform = Platform{}
	r.Policy.DNS.Remote = DNSServer{}
	p := compile(t, r)
	if len(p.Options.Inbounds) != 0 || len(p.Options.Route.Rules) != 0 || p.Options.DNS.Final != "dns-direct" {
		t.Fatal("probe must have no listener or run routing side effects")
	}
}
func TestRejectsAmbiguousOrInvalidPolicy(t *testing.T) {
	changes := []func(*Request){func(r *Request) { r.Profiles = append(r.Profiles, r.Profiles[0]) }, func(r *Request) { r.SelectedID = "missing" }, func(r *Request) { r.Policy.IPv6 = "magic" }, func(r *Request) { r.Platform.MTU = 1 }, func(r *Request) { r.Policy.DNS.Remote.Server = "https://dns.example/path" }, func(r *Request) { r.Policy.Rules = []Rule{{ID: "empty", Action: "reject"}} }, func(r *Request) {
		r.Policy.Rules = []Rule{{ID: "bad", Match: Match{Ports: []uint16{0}}, Action: "reject"}}
	}, func(r *Request) {
		r.Platform.SupportsUIDRules = false
		r.Policy.Rules = []Rule{{ID: "app", Match: Match{UIDs: []uint32{10001}}, Action: "reject"}}
	}, func(r *Request) {
		r.Policy.Rules = []Rule{{ID: "bad", Match: Match{Domains: []string{"example.com"}}, Action: "route", Target: &Target{Kind: "direct", ID: "ignored"}}}
	}}
	for i, change := range changes {
		r := request()
		change(&r)
		if _, e := Compile(r); e == nil {
			t.Fatalf("accepted invalid policy %d", i)
		}
	}
}
func TestTransportHeadersAndInputIsolation(t *testing.T) {
	r := request()
	r.Profiles[0] = profile.Profile{ID: "a", Type: "vless", Server: "proxy.example", Port: 443, VLESS: &profile.VLESS{UUID: "00000000-0000-4000-8000-000000000001", PacketEncoding: "xudp"}, Transport: &profile.Transport{Type: "ws", Path: "/", Headers: map[string][]string{"X-Test": {"a"}}}}
	before, _ := json.Marshal(r)
	p := compile(t, r)
	out := p.Options.Outbounds[0].Options.(*option.VLESSOutboundOptions)
	if out.Transport.WebsocketOptions.Headers["X-Test"][0] != "a" || out.PacketEncoding == nil || *out.PacketEncoding != "xudp" {
		t.Fatal("transport or packet option was discarded")
	}
	out.Transport.WebsocketOptions.Headers["X-Test"][0] = "mutated"
	after, _ := json.Marshal(r)
	if !reflect.DeepEqual(before, after) {
		t.Fatal("plan aliases input")
	}
	r.Profiles[0].Transport = &profile.Transport{Type: "tcp"}
	p = compile(t, r)
	if p.Options.Outbounds[0].Options.(*option.VLESSOutboundOptions).Transport != nil {
		t.Fatal("TCP needs no V2Ray transport object")
	}
}
func TestConcurrentDeterministicCompilation(t *testing.T) {
	r := request()
	want := compile(t, r).Config
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p, e := Compile(r)
			if e != nil || p.Config != want {
				t.Error("compilation is not deterministic", e)
			}
		}()
	}
	wg.Wait()
}

func TestDNSFamilyPolicyDoesNotRemovePlatformTUNAddresses(t *testing.T) {
	for _, strategy := range []string{"ipv4_only", "ipv6_only"} {
		r := request()
		r.Policy.IPv6 = strategy
		p := compile(t, r)
		tun := p.Options.Inbounds[0].Options.(*option.TunInboundOptions)
		if len(tun.Address) != len(r.Platform.TUNAddresses) {
			t.Fatalf("%s removed a platform address", strategy)
		}
		for i, prefix := range tun.Address {
			if prefix.String() != r.Platform.TUNAddresses[i] {
				t.Fatal("platform address changed")
			}
		}
		want, _ := domainStrategy(strategy)
		if p.Options.DNS.Strategy != want {
			t.Fatal("upstream DNS family policy was lost")
		}
	}
}
