package libcore

import (
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/killertop/Vialen/core/compiler"
	"github.com/killertop/Vialen/core/importer"
	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// The tests construct and close the production registry-backed Box without
// Start. No listener, TUN, DNS exchange, or upstream connection is requested.
// All endpoint/DNS addresses are loopback even if construction ever changes.
// The compiler requires a mixed-inbound declaration for Run, but it remains unstarted.
type newClientConstructionPlatform struct {
	dummyPlatformInterface
	tunCalls int
}

func (p *newClientConstructionPlatform) OpenTun(string, string) (int, error) {
	p.tunCalls++
	return -1, fmt.Errorf("TUN must not open during construction")
}
func newClientPlatform(t *testing.T) *newClientConstructionPlatform {
	t.Helper()
	old := intfBox
	p := &newClientConstructionPlatform{}
	intfBox = p
	t.Cleanup(func() {
		intfBox = old
		if p.tunCalls != 0 {
			t.Errorf("construction opened TUN %d times", p.tunCalls)
		}
	})
	return p
}
func newClientImport(t *testing.T, format, text string) []profile.Profile {
	t.Helper()
	r, e := importer.Parse(importer.Request{Format: format, Text: text})
	if e != nil {
		t.Fatal(e)
	}
	if r.HasErrors() || len(r.Profiles) == 0 {
		t.Fatalf("profile import failed: %+v", r.Issues)
	}
	for i := range r.Profiles {
		r.Profiles[i].ID = fmt.Sprintf("node-%d", i)
	}
	return r.Profiles
}
func newClientRequest(profiles []profile.Profile) compiler.Request {
	return compiler.Request{Profiles: profiles, SelectedID: profiles[0].ID, Purpose: compiler.Run, Policy: compiler.Policy{DNS: compiler.DNSPolicy{Direct: compiler.DNSServer{Type: "udp", Server: "127.0.0.1", Port: 5353}, Remote: compiler.DNSServer{Type: "https", Server: "127.0.0.1", Port: 8443, Path: "/dns-query"}, RouteDomains: true}}, Platform: compiler.Platform{VPN: false, MixedPort: 17491}}
}
func newClientConstruct(t *testing.T, r compiler.Request) compiler.Plan {
	t.Helper()
	plan, e := compiler.Compile(r)
	if e != nil {
		t.Fatal(e)
	}
	if len(plan.Options.Inbounds) != 1 || plan.Options.Inbounds[0].Type != "mixed" {
		t.Fatal("fixture must declare only a dormant mixed inbound, never TUN")
	}
	// Run plans require an inbound declaration. Box.New constructs it without
	// binding a port; Start is deliberately never called.
	instance, e := NewSingBoxInstance(plan.Config, nil)
	if e != nil {
		t.Fatalf("typed plan rejected by pinned production registry: %v", e)
	}
	if instance == nil || instance.Box == nil {
		t.Fatal("nil core instance")
	}
	if instance.started {
		t.Fatal("constructor unexpectedly started core")
	}
	if e := instance.Close(); e != nil {
		t.Fatalf("close unstarted core: %v", e)
	}
	return plan
}

func TestNewClientImportedProtocolsInitializePinnedCore(t *testing.T) {
	newClientPlatform(t)
	private, e := ecdh.X25519().NewPrivateKey(bytes.Repeat([]byte{7}, 32))
	if e != nil {
		t.Fatal(e)
	}
	privateKey := base64.StdEncoding.EncodeToString(private.Bytes())
	publicKey := base64.StdEncoding.EncodeToString(private.PublicKey().Bytes())
	const uuid = "00000000-0000-4000-8000-000000000001"
	cases := []struct{ kind, options, want string }{{"socks5", "username: user, password: synthetic", "socks"}, {"http", "username: user, password: synthetic, tls: true, sni: tls.example", "http"}, {"ss", "cipher: aes-128-gcm, password: synthetic", "shadowsocks"}, {"vmess", "uuid: " + uuid + ", cipher: auto, alterId: 0, packet-encoding: xudp", "vmess"}, {"vless", "uuid: " + uuid + ", tls: true, servername: tls.example", "vless"}, {"trojan", "password: synthetic, sni: tls.example", "trojan"}, {"hysteria2", "password: synthetic, sni: tls.example, obfs: salamander, obfs-password: synthetic-obfs", "hysteria2"}, {"tuic", "uuid: " + uuid + ", password: synthetic, sni: tls.example, congestion-controller: bbr", "tuic"}, {"anytls", "password: synthetic, sni: tls.example", "anytls"}, {"wireguard", "private-key: " + privateKey + ", public-key: " + publicKey + ", ip: 10.0.0.2, allowed-ips: [0.0.0.0/0], reserved: [0, 1, 255]", "wireguard"}}
	for _, c := range cases {
		t.Run(c.want, func(t *testing.T) {
			profiles := newClientImport(t, "clash", "proxies: [{type: "+c.kind+", name: synthetic, server: 127.0.0.1, port: 443, "+c.options+"}]")
			if len(profiles) != 1 || profiles[0].Type != c.want {
				t.Fatal("wrong imported protocol")
			}
			plan := newClientConstruct(t, newClientRequest(profiles))
			if c.want == "wireguard" {
				if len(plan.Options.Endpoints) != 1 || plan.Options.Endpoints[0].Type != "wireguard" {
					t.Fatal("WireGuard was not compiled as an endpoint")
				}
			} else {
				found := false
				for _, outbound := range plan.Options.Outbounds {
					if outbound.Type == c.want {
						found = true
					}
				}
				if !found {
					t.Fatal("protocol option missing")
				}
			}
		})
	}
}

func TestNewClientRealityImplicitAndExplicitFingerprintsInitializePinnedCore(t *testing.T) {
	newClientPlatform(t)
	const uri = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?security=reality&sni=synthetic.example&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
	for _, fingerprint := range []string{"", "firefox"} {
		t.Run("fingerprint="+fingerprint, func(t *testing.T) {
			profiles := newClientImport(t, "links", uri+"&fp="+fingerprint)
			plan := newClientConstruct(t, newClientRequest(profiles))
			tls := plan.Options.Outbounds[0].Options.(*option.VLESSOutboundOptions).TLS
			want := fingerprint
			if want == "" {
				want = "chrome"
			}
			if tls.UTLS == nil || !tls.UTLS.Enabled || tls.UTLS.Fingerprint != want {
				t.Fatalf("Reality uTLS fingerprint: got %+v, want %s", tls.UTLS, want)
			}
			if profiles[0].TLS.Fingerprint != fingerprint {
				t.Fatal("compilation changed the saved profile")
			}
		})
	}
}

func TestNewClientDNSVariantsInitializePinnedCore(t *testing.T) {
	newClientPlatform(t)
	profiles := newClientImport(t, "links", "socks5://127.0.0.1:1080")
	for _, kind := range []string{"local", "udp", "tcp", "tls", "https", "quic", "h3"} {
		t.Run(kind, func(t *testing.T) {
			r := newClientRequest(profiles)
			dns := compiler.DNSServer{Type: kind, Server: "127.0.0.1", Port: 5353}
			if kind == "local" {
				dns.Server = ""
				dns.Port = 0
			}
			if kind == "https" || kind == "h3" {
				dns.Path = "/dns-query"
			}
			r.Policy.DNS.Direct = dns
			r.Policy.DNS.Remote = dns
			newClientConstruct(t, r)
		})
	}
}

func TestNewClientSelectorChainAndLocalRuleSetsInitializePinnedCore(t *testing.T) {
	newClientPlatform(t)
	profiles := newClientImport(t, "links", "socks5://127.0.0.1:1080#first\nhttp://127.0.0.1:8080#second")
	r := newClientRequest(profiles)
	r.Chains = []compiler.Chain{{ID: "chain", Hops: []string{profiles[0].ID, profiles[1].ID}}}
	r.SelectedID = "chain"
	r.SelectorIDs = []string{"chain", profiles[0].ID, profiles[1].ID}
	dir := t.TempDir()
	sourcePath := filepath.Join(dir, "source.json")
	source, e := json.Marshal(map[string]any{"version": C.RuleSetVersionCurrent, "rules": []any{map[string]any{"domain_suffix": []string{"fixture.invalid"}}}})
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(sourcePath, source, 0600); e != nil {
		t.Fatal(e)
	}
	binaryPath := filepath.Join(dir, "binary.srs")
	file, e := os.Create(binaryPath)
	if e != nil {
		t.Fatal(e)
	}
	e = srs.Write(file, option.PlainRuleSet{Rules: []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultHeadlessRule{IPCIDR: []string{"192.0.2.0/24"}}}}}, C.RuleSetVersionCurrent)
	closeErr := file.Close()
	if e != nil {
		t.Fatal(e)
	}
	if closeErr != nil {
		t.Fatal(closeErr)
	}
	r.RuleSets = []compiler.RuleSet{{ID: "domains", Type: "local", Format: "source", Path: sourcePath}, {ID: "ranges", Type: "local", Format: "binary", Path: binaryPath}}
	r.Policy.Sniff = true
	r.Policy.ResolveDestination = true
	r.Policy.BypassLAN = true
	r.Policy.Rules = []compiler.Rule{{ID: "direct-domain", Match: compiler.Match{DomainSuffixes: []string{"direct.invalid"}}, Action: "route", Target: &compiler.Target{Kind: "direct"}}, {ID: "selected-ruleset", Match: compiler.Match{RuleSetIDs: []string{"domains"}}, Action: "route", Target: &compiler.Target{Kind: "selected"}}, {ID: "reject-ranges", Match: compiler.Match{RuleSetIDs: []string{"ranges"}}, Action: "reject"}}
	plan := newClientConstruct(t, r)
	if len(plan.Metadata.SelectorCandidates) != 3 || len(plan.Metadata.RuleSets) != 2 {
		t.Fatalf("missing typed metadata: %+v", plan.Metadata)
	}
	if len(plan.Options.Route.RuleSet) != 2 {
		t.Fatal("rule sets missing from runtime plan")
	}
	if len(plan.Options.DNS.Rules) == 0 {
		t.Fatal("domain DNS projection missing")
	}
	hops := 0
	for _, binding := range plan.Metadata.Bindings {
		if binding.ReferenceID == "chain" {
			hops++
		}
	}
	if hops != 2 {
		t.Fatalf("chain bindings=%d", hops)
	}
}

func TestNewClientRawChainAndDNSStrategyFixtureInitializesPinnedCore(t *testing.T) {
	newClientPlatform(t)
	raw, err := os.ReadFile("../core/compiler/testdata/raw_chain_strategy.json")
	if err != nil {
		t.Fatal(err)
	}
	var request compiler.Request
	if err = json.Unmarshal(raw, &request); err != nil {
		t.Fatal(err)
	}
	// Only use loopback DNS; preserve strategy, rule, raw fields, and chain graph.
	request.Policy.DNS.Direct.Server = "127.0.0.1"
	request.Policy.DNS.Remote.Server = "127.0.0.1"
	plan := newClientConstruct(t, request)
	if len(plan.Metadata.Bindings) != 2 {
		t.Fatal("raw chain lost member")
	}
}

func TestNewClientAdvancedFormOptionsInitializePinnedCore(t *testing.T) {
	newClientPlatform(t)
	const uuid = "00000000-0000-4000-8000-000000000001"
	profiles := []profile.Profile{
		{Type: "socks", Socks: &profile.Socks{Version: "5"}, UDPOverTCP: &profile.UDPOverTCP{Enabled: true, Version: 1}},
		{Type: "vmess", VMess: &profile.VMess{UUID: uuid}, Multiplex: &profile.Multiplex{Enabled: true, Protocol: "smux", MaxStreams: 8, Padding: true}, TLS: &profile.TLS{Enabled: true, ECH: &profile.ECH{Enabled: true, QueryServerName: "ech.example"}}},
		{Type: "hysteria2", Hysteria2: &profile.Hysteria2{Password: "synthetic", HopInterval: 10, HopIntervalMax: 20, BBRProfile: "conservative", DisableChromeParrot: true, Obfs: &profile.Obfs{Type: "gecko", Password: "synthetic", MinPacketSize: 512, MaxPacketSize: 1200}}, TLS: &profile.TLS{Enabled: true}},
		{Type: "tuic", TUIC: &profile.TUIC{UUID: uuid, Password: "synthetic"}, TLS: &profile.TLS{Enabled: true, DisableSNI: true}},
		{Type: "shadowtls", ShadowTLS: &profile.ShadowTLS{Version: 3, Password: "synthetic"}, TLS: &profile.TLS{Enabled: true, ServerName: "cover.example"}},
	}
	for _, p := range profiles {
		t.Run(p.Type, func(t *testing.T) {
			p.ID = "node"
			p.Server = "127.0.0.1"
			p.Port = 443
			newClientConstruct(t, newClientRequest([]profile.Profile{p}))
		})
	}
}

func TestNewClientCompiledSelectorActuallySwitches(t *testing.T) {
	newClientPlatform(t)
	profiles := newClientImport(t, "links", "socks5://127.0.0.1:1080#first\nhttp://127.0.0.1:8080#second")
	r := newClientRequest(profiles)
	r.Purpose = compiler.Probe
	r.Platform.MixedPort = 0
	r.SelectorIDs = []string{profiles[0].ID, profiles[1].ID}
	plan, err := compiler.Compile(r)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Options.Inbounds) != 0 {
		t.Fatal("selector test must not open listeners")
	}
	instance, err := NewSingBoxInstance(plan.Config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := instance.Close(); err != nil {
			t.Error(err)
		}
	}()
	if instance.selector == nil || instance.selector.Tag() != plan.Metadata.SelectedTag {
		t.Fatal("compiled selector was not discovered by runtime adapter")
	}
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	first := plan.Metadata.SelectorCandidates[0].Tag
	second := plan.Metadata.SelectorCandidates[1].Tag
	if instance.selector.Now() != first {
		t.Fatal("wrong initial candidate")
	}
	if !instance.SelectOutbound(second) || instance.selector.Now() != second {
		t.Fatal("selector did not switch to requested candidate")
	}
	if instance.SelectOutbound("missing") || instance.selector.Now() != second {
		t.Fatal("invalid selection mutated state")
	}
	if err = instance.Close(); err != nil {
		t.Fatal(err)
	}
	// A prior core session must not override Android's next selected_id.
	next, err := NewSingBoxInstance(plan.Config, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	if err = next.Start(); err != nil {
		t.Fatal(err)
	}
	if next.selector.Now() != first {
		t.Fatal("previous core cache overrode request default")
	}
}

func TestNewClientDNSFamilyPolicyPreservesPlatformDNSIngress(t *testing.T) {
	platform := newClientPlatform(t)
	for _, strategy := range []string{"ipv4_only", "ipv6_only"} {
		r := newClientRequest(newClientImport(t, "links", "socks5://127.0.0.1:1080"))
		r.Policy.IPv6 = strategy
		r.Platform = compiler.Platform{VPN: true, TUNAddresses: []string{"172.19.0.1/30", "fdfe:dcba:9876::1/126"}, Stack: "system"}
		plan, err := compiler.Compile(r)
		if err != nil {
			t.Fatal(err)
		}
		tun := plan.Options.Inbounds[0].Options.(*option.TunInboundOptions)
		if len(tun.Address) != 2 || !tun.Address[0].Addr().Is4() {
			t.Fatal("virtual IPv4 DNS entry point missing")
		}
		instance, err := NewSingBoxInstance(plan.Config, nil)
		if err != nil {
			t.Fatal(err)
		}
		if err = instance.Close(); err != nil {
			t.Fatal(err)
		}
		if platform.tunCalls != 0 {
			t.Fatal("option validation opened platform TUN")
		}
	}
}
