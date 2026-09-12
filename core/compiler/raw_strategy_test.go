package compiler

import (
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"

	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
)

func TestRawOutboundIsTypedChainMember(t *testing.T) {
	r := request()
	r.RawOutbounds = []RawOutbound{{ID: "raw", JSON: json.RawMessage(`{"type":"http","server":"proxy.example","server_port":8080,"username":"user","headers":{"X-Custom":"value"}}`)}}
	r.Chains = []Chain{{ID: "chain", Hops: []string{"a", "raw"}}}
	r.SelectedID = "chain"
	before := string(r.RawOutbounds[0].JSON)
	p := compile(t, r)
	if _, ok := p.Options.Outbounds[1].Options.(*option.HTTPOutboundOptions); !ok {
		t.Fatal("raw outbound did not become authoritative option type")
	}
	first := p.Options.Outbounds[0].Options.(option.DialerOptionsWrapper).TakeDialerOptions()
	if first.Detour != p.Options.Outbounds[1].Tag {
		t.Fatal("chain detour not connected")
	}
	if string(r.RawOutbounds[0].JSON) != before || !strings.Contains(p.Config, "X-Custom") {
		t.Fatal("raw input mutated or advanced fields lost")
	}
	if p.Metadata.Bindings[1].ProfileID != "raw" {
		t.Fatal("raw member missing statistics metadata")
	}
}
func TestRawOutboundRejectsNamespaceEscapeAndUnknownFields(t *testing.T) {
	for _, extra := range []string{`,"tag":"stolen"`, `,"detour":"unknown"`, `,"domain_resolver":"unknown"`, `,"unexpected_field":true`} {
		r := request()
		r.RawOutbounds = []RawOutbound{{ID: "raw", JSON: json.RawMessage(`{"type":"socks","server":"127.0.0.1","server_port":1080` + extra + `}`)}}
		if _, err := Compile(r); err == nil {
			t.Fatal("accepted raw namespace escape", extra)
		}
	}
	for _, kind := range []string{"selector", "urltest", "wireguard", "dns", "block"} {
		if _, err := decodeRaw(RawOutbound{JSON: json.RawMessage(`{"type":"` + kind + `"}`)}); err == nil {
			t.Fatal("accepted unmanaged reference or deprecated outbound", kind)
		}
	}
	r := request()
	r.RawOutbounds = []RawOutbound{{ID: "a", JSON: json.RawMessage(`{"type":"direct"}`)}}
	if _, err := Compile(r); err == nil {
		t.Fatal("duplicate profile/raw id accepted")
	}
}
func TestSeparateAddressAndDNSStrategies(t *testing.T) {
	r := request()
	r.Policy.IPv6 = "prefer_ipv4"
	r.Policy.ServerStrategy = "ipv6_only"
	r.Policy.DNS.Direct.Strategy = "ipv4_only"
	r.Policy.DNS.Remote.Strategy = "prefer_ipv6"
	r.Policy.Rules = []Rule{{ID: "direct", Match: Match{Domains: []string{"example.com"}}, Action: "route", Target: &Target{Kind: "direct"}}}
	p := compile(t, r)
	server, _ := domainStrategy("ipv6_only")
	direct, _ := domainStrategy("ipv4_only")
	remote, _ := domainStrategy("prefer_ipv6")
	node := p.Options.Outbounds[0].Options.(option.DialerOptionsWrapper).TakeDialerOptions()
	if node.DomainResolver.Strategy != server || p.Options.DNS.Strategy != remote || p.Options.DNS.Rules[0].DefaultOptions.RouteOptions.Strategy != direct {
		t.Fatal("independent strategies were not retained")
	}
	r.Policy.ServerStrategy = "invalid"
	if _, err := Compile(r); err == nil {
		t.Fatal("invalid strategy accepted")
	}
}
func TestShadowTLSCanBeAChainTransport(t *testing.T) {
	r := request()
	r.Profiles = append(r.Profiles, profile.Profile{ID: "tls", Type: "shadowtls", Server: "shadow.example", Port: 443, ShadowTLS: &profile.ShadowTLS{Version: 3, Password: "secret"}, TLS: &profile.TLS{Enabled: true, ServerName: "cover.example"}})
	r.Chains = []Chain{{ID: "chain", Hops: []string{"a", "tls"}}}
	r.SelectedID = "chain"
	p := compile(t, r)
	o, ok := p.Options.Outbounds[1].Options.(*option.ShadowTLSOutboundOptions)
	if !ok || o.Version != 3 || o.Password != "secret" || !reflect.DeepEqual(p.Metadata.Bindings[1].ProfileID, "tls") {
		t.Fatal("ShadowTLS options lost")
	}
}

// Shared fixture for compiler checks and libcore runtime validation.
func TestRuntimeValidationFixtureCompiles(t *testing.T) {
	raw, err := os.ReadFile("testdata/raw_chain_strategy.json")
	if err != nil {
		t.Fatal(err)
	}
	var r Request
	if err = json.Unmarshal(raw, &r); err != nil {
		t.Fatal(err)
	}
	compile(t, r)
}
