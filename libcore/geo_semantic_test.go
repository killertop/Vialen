package libcore

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
)

func writeSRS(t *testing.T, path string, cidrs []string, domains []string) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatalf("Failed to create SRS file %s: %v", path, err)
	}
	defer f.Close()

	var rules []option.HeadlessRule
	if len(cidrs) > 0 {
		rules = append(rules, option.HeadlessRule{
			Type: C.RuleTypeDefault,
			DefaultOptions: option.DefaultHeadlessRule{
				IPCIDR: cidrs,
			},
		})
	}
	if len(domains) > 0 {
		rules = append(rules, option.HeadlessRule{
			Type: C.RuleTypeDefault,
			DefaultOptions: option.DefaultHeadlessRule{
				Domain: domains,
			},
		})
	}
	err = srs.Write(f, option.PlainRuleSet{
		Rules: rules,
	}, C.RuleSetVersionCurrent)
	if err != nil {
		t.Fatalf("Failed to write SRS: %v", err)
	}
}

func dnsResponse(addrs ...netip.Addr) *mDNS.Msg {
	response := &mDNS.Msg{
		MsgHdr: mDNS.MsgHdr{
			Response: true,
			Rcode:    mDNS.RcodeSuccess,
		},
	}
	for _, address := range addrs {
		if address.Is4() {
			response.Answer = append(response.Answer, &mDNS.A{
				Hdr: mDNS.RR_Header{
					Name:   mDNS.Fqdn("lookup.example.com"),
					Rrtype: mDNS.TypeA,
					Class:  mDNS.ClassINET,
					Ttl:    60,
				},
				A: net.IP(append([]byte(nil), address.AsSlice()...)),
			})
		} else {
			response.Answer = append(response.Answer, &mDNS.AAAA{
				Hdr: mDNS.RR_Header{
					Name:   mDNS.Fqdn("lookup.example.com"),
					Rrtype: mDNS.TypeAAAA,
					Class:  mDNS.ClassINET,
					Ttl:    60,
				},
				AAAA: net.IP(append([]byte(nil), address.AsSlice()...)),
			})
		}
	}
	return response
}

func TestPhaseD_RoutingSemantics(t *testing.T) {
	tempDir := t.TempDir()

	geoIPPath := filepath.Join(tempDir, "geoip-cn.srs")
	geoSitePath := filepath.Join(tempDir, "geosite-cn.srs")
	geoSrcPath := filepath.Join(tempDir, "geoip-source.srs")

	writeSRS(t, geoIPPath, []string{"203.0.113.0/24", "198.51.100.0/24"}, nil)
	writeSRS(t, geoSitePath, nil, []string{"cn-site.example.com", "bypass.internal"})
	writeSRS(t, geoSrcPath, []string{"192.168.10.0/24"}, nil)

	configTemplate := fmt.Sprintf(`{
		"log": {"level": "warn"},
		"outbounds": [
			{"type": "direct", "tag": "direct"},
			{"type": "direct", "tag": "bypass"},
			{"type": "direct", "tag": "proxy"}
		],
		"route": {
			"rule_set": [
				{
					"type": "local",
					"tag": "geoip-cn",
					"format": "binary",
					"path": "%s"
				},
				{
					"type": "local",
					"tag": "geosite-cn",
					"format": "binary",
					"path": "%s"
				},
				{
					"type": "local",
					"tag": "geoip-source",
					"format": "binary",
					"path": "%s"
				}
			],
			"rules": [
				{
					"rule_set": ["geoip-source"],
					"rule_set_ip_cidr_match_source": true,
					"outbound": "proxy"
				},
				{
					"ip_is_private": true,
					"outbound": "bypass"
				},
				{
					"rule_set": ["geosite-cn", "geoip-cn"],
					"outbound": "bypass"
				}
			]
		}
	}`, geoIPPath, geoSitePath, geoSrcPath)

	intfBox = &dummyPlatformInterface{}
	instance, err := NewSingBoxInstance(configTemplate, nil)
	if err != nil {
		t.Fatalf("NewSingBoxInstance failed: %v", err)
	}
	if instance == nil {
		t.Fatalf("Expected non-nil instance")
	}
	defer instance.Close()

	err = instance.Start()
	if err != nil {
		t.Fatalf("instance.Start() failed: %v", err)
	}

	rules := instance.Box.Router().Rules()
	if len(rules) != 3 {
		t.Fatalf("Expected 3 rules, got %d", len(rules))
	}

	// Rule 0: SOURCE_GEOIP (rule_set: geoip-source, rule_set_ip_cidr_match_source: true -> proxy)
	// Rule 1: PRIVATE_IP (ip_is_private: true -> bypass)
	// Rule 2: COMBINED DESTINATION GEOIP + GEOSITE (rule_set: [geosite-cn, geoip-cn] -> bypass)

	t.Run("SEMANTIC_SOURCE_GEOIP", func(t *testing.T) {
		// Case A: Source in 192.168.10.0/24, destination arbitrary public IP
		metaMatch := adapter.InboundContext{
			Source:      M.ParseSocksaddr("192.168.10.50:54321"),
			Destination: M.ParseSocksaddr("8.8.8.8:443"),
		}
		if !rules[0].Match(&metaMatch) {
			t.Fatalf("Must match source GeoIP rule when source IP is in range")
		}

		// Case B: Destination in 192.168.10.0/24, but Source NOT in range
		metaMismatch := adapter.InboundContext{
			Source:      M.ParseSocksaddr("10.0.0.1:54321"),
			Destination: M.ParseSocksaddr("192.168.10.50:443"),
		}
		if rules[0].Match(&metaMismatch) {
			t.Fatalf("Source GeoIP rule must NOT match when only destination IP matches!")
		}
	})

	t.Run("SEMANTIC_PRIVATE_IP", func(t *testing.T) {
		metaPrivate4 := adapter.InboundContext{
			Destination: M.ParseSocksaddr("192.168.1.1:80"),
		}
		if !rules[1].Match(&metaPrivate4) {
			t.Fatalf("ip_is_private must match 192.168.1.1")
		}

		metaPrivate10 := adapter.InboundContext{
			Destination: M.ParseSocksaddr("10.200.1.1:80"),
		}
		if !rules[1].Match(&metaPrivate10) {
			t.Fatalf("ip_is_private must match 10.200.1.1")
		}

		metaPublic := adapter.InboundContext{
			Destination: M.ParseSocksaddr("1.1.1.1:80"),
		}
		if rules[1].Match(&metaPublic) {
			t.Fatalf("ip_is_private must NOT match public IP 1.1.1.1")
		}
	})

	t.Run("SEMANTIC_DESTINATION_GEOIP", func(t *testing.T) {
		metaDest := adapter.InboundContext{
			Destination: M.ParseSocksaddr("203.0.113.5:443"),
		}
		if !rules[2].Match(&metaDest) {
			t.Fatalf("Must match destination GeoIP rule")
		}

		metaOther := adapter.InboundContext{
			Destination: M.ParseSocksaddr("8.8.8.8:443"),
		}
		if rules[2].Match(&metaOther) {
			t.Fatalf("Must not match destination GeoIP rule for non-matching IP")
		}
	})

	t.Run("SEMANTIC_GEOSITE_DOMAIN", func(t *testing.T) {
		metaDomain := adapter.InboundContext{
			Domain:      "cn-site.example.com",
			Destination: M.ParseSocksaddr("1.1.1.1:443"),
		}
		if !rules[2].Match(&metaDomain) {
			t.Fatalf("Must match geosite domain rule")
		}

		metaDomainOther := adapter.InboundContext{
			Domain:      "google.com",
			Destination: M.ParseSocksaddr("1.1.1.1:443"),
		}
		if rules[2].Match(&metaDomainOther) {
			t.Fatalf("Must not match geosite domain rule for other domains")
		}
	})

	t.Run("SEMANTIC_RULE_ORDER_AND_FALLBACK", func(t *testing.T) {
		// When source matches Rule 0, Rule 0 wins even if destination would also match Rule 2
		metaConflict := adapter.InboundContext{
			Source:      M.ParseSocksaddr("192.168.10.50:54321"),
			Destination: M.ParseSocksaddr("203.0.113.5:443"),
		}
		if !rules[0].Match(&metaConflict) {
			t.Fatalf("Rule 0 must match first")
		}

		// Traffic with no matching rule does not match any
		metaNoMatch := adapter.InboundContext{
			Source:      M.ParseSocksaddr("172.25.0.1:1234"),
			Destination: M.ParseSocksaddr("1.1.1.1:443"),
			Domain:      "unmatched.org",
		}
		matchedAny := false
		for _, r := range rules {
			if r.Match(&metaNoMatch) {
				matchedAny = true
				break
			}
		}
		if matchedAny {
			t.Fatalf("Non-matching traffic must fall through all rules")
		}
	})
}

func TestPhaseD_DNSResponseMatchingSemantics(t *testing.T) {
	tempDir := t.TempDir()
	geoIPPath := filepath.Join(tempDir, "geoip-cn.srs")
	writeSRS(t, geoIPPath, []string{"203.0.113.0/24"}, nil)

	configTemplate := fmt.Sprintf(`{
		"log": {"level": "warn"},
		"outbounds": [{"type": "direct", "tag": "direct"}],
		"route": {
			"rule_set": [
				{
					"type": "local",
					"tag": "geoip-cn",
					"format": "binary",
					"path": "%s"
				}
			],
			"rules": [
				{
					"rule_set": ["geoip-cn"],
					"outbound": "direct"
				}
			]
		}
	}`, geoIPPath)

	intfBox = &dummyPlatformInterface{}
	instance, err := NewSingBoxInstance(configTemplate, nil)
	if err != nil {
		t.Fatalf("NewSingBoxInstance failed: %v", err)
	}
	defer instance.Close()

	err = instance.Start()
	if err != nil {
		t.Fatalf("instance.Start() failed: %v", err)
	}

	router := instance.Box.Router()
	ctx := service.ContextWith[adapter.Router](context.Background(), router)
	logger := log.NewNOPFactory().NewLogger("dns-test")

	// 1. DNS Rule with match_response: true + rule_set: ["geoip-cn"]
	var ruleOpt1 option.DefaultDNSRule
	err = ruleOpt1.UnmarshalJSONContext(ctx, []byte(`{
		"match_response": true,
		"rule_set": ["geoip-cn"],
		"server": "dns-direct"
	}`))
	if err != nil {
		t.Fatalf("Unmarshal ruleOpt1 failed: %v", err)
	}
	dnsRule1, err := rule.NewDNSRule(ctx, logger, option.DNSRule{Type: C.RuleTypeDefault, DefaultOptions: ruleOpt1}, false, false)
	if err != nil {
		t.Fatalf("NewDNSRule 1 failed: %v", err)
	}
	err = dnsRule1.Start()
	if err != nil {
		t.Fatalf("dnsRule1 Start failed: %v", err)
	}
	defer dnsRule1.Close()

	t.Run("SEMANTIC_DNS_RESPONSE_RULESET", func(t *testing.T) {
		metaMatch := adapter.InboundContext{
			Domain:      "lookup.example.com",
			DNSResponse: dnsResponse(netip.MustParseAddr("203.0.113.50")),
		}
		if !dnsRule1.Match(&metaMatch) {
			t.Fatalf("Must match resolved response IP in geoip-cn")
		}

		metaMismatch := adapter.InboundContext{
			Domain:      "lookup.example.com",
			DNSResponse: dnsResponse(netip.MustParseAddr("8.8.8.8")),
		}
		if dnsRule1.Match(&metaMismatch) {
			t.Fatalf("Must not match non-matching response IP")
		}
	})

	// 2. DNS Rule with match_response: true + ip_is_private: true
	var ruleOpt2 option.DefaultDNSRule
	err = ruleOpt2.UnmarshalJSONContext(ctx, []byte(`{
		"match_response": true,
		"ip_is_private": true,
		"server": "dns-direct"
	}`))
	if err != nil {
		t.Fatalf("Unmarshal ruleOpt2 failed: %v", err)
	}
	dnsRule2, err := rule.NewDNSRule(ctx, logger, option.DNSRule{Type: C.RuleTypeDefault, DefaultOptions: ruleOpt2}, false, false)
	if err != nil {
		t.Fatalf("NewDNSRule 2 failed: %v", err)
	}
	err = dnsRule2.Start()
	if err != nil {
		t.Fatalf("dnsRule2 Start failed: %v", err)
	}
	defer dnsRule2.Close()

	t.Run("SEMANTIC_DNS_RESPONSE_PRIVATE_IP", func(t *testing.T) {
		metaMatch := adapter.InboundContext{
			Domain:      "router.lan",
			DNSResponse: dnsResponse(netip.MustParseAddr("192.168.1.1")),
		}
		if !dnsRule2.Match(&metaMatch) {
			t.Fatalf("Must match private response IP")
		}

		metaMismatch := adapter.InboundContext{
			Domain:      "public.com",
			DNSResponse: dnsResponse(netip.MustParseAddr("1.1.1.1")),
		}
		if dnsRule2.Match(&metaMismatch) {
			t.Fatalf("Must not match public response IP")
		}
	})

	// 3. DNS Rule with match_response: true + ip_cidr: ["198.51.100.0/24"] -> reject
	var ruleOpt3 option.DefaultDNSRule
	err = ruleOpt3.UnmarshalJSONContext(ctx, []byte(`{
		"action": "reject",
		"match_response": true,
		"ip_cidr": ["198.51.100.0/24"]
	}`))
	if err != nil {
		t.Fatalf("Unmarshal ruleOpt3 failed: %v", err)
	}
	dnsRule3, err := rule.NewDNSRule(ctx, logger, option.DNSRule{Type: C.RuleTypeDefault, DefaultOptions: ruleOpt3}, false, false)
	if err != nil {
		t.Fatalf("NewDNSRule 3 failed: %v", err)
	}
	err = dnsRule3.Start()
	if err != nil {
		t.Fatalf("dnsRule3 Start failed: %v", err)
	}
	defer dnsRule3.Close()

	t.Run("SEMANTIC_DNS_RESPONSE_IP_CIDR_REJECT", func(t *testing.T) {
		metaMatch := adapter.InboundContext{
			Domain:      "bad.example.com",
			DNSResponse: dnsResponse(netip.MustParseAddr("198.51.100.22")),
		}
		if !dnsRule3.Match(&metaMatch) {
			t.Fatalf("Must match IP-CIDR response rule")
		}
		if !strings.HasPrefix(dnsRule3.Action().String(), "reject") {
			t.Fatalf("Action must be reject, got %s", dnsRule3.Action().String())
		}
	})
}

func TestPhaseD_CustomConfigLegacyRejection(t *testing.T) {
	tempDir := t.TempDir()
	dummyPath := filepath.Join(tempDir, "dummy.srs")
	writeSRS(t, dummyPath, []string{"1.1.1.1/32"}, nil)

	intfBox = &dummyPlatformInterface{}

	testCases := []struct {
		name        string
		config      string
		errContains string
	}{
		{
			name: "LegacyRouteGeoIP",
			config: `{
				"outbounds": [{"type": "direct", "tag": "direct"}],
				"route": {
					"rules": [{"geoip": ["cn"], "outbound": "direct"}]
				}
			}`,
			errContains: "geoip database is deprecated in sing-box 1.8.0 and removed in sing-box 1.12.0",
		},
		{
			name: "LegacyRouteGeosite",
			config: `{
				"outbounds": [{"type": "direct", "tag": "direct"}],
				"route": {
					"rules": [{"geosite": ["cn"], "outbound": "direct"}]
				}
			}`,
			errContains: "geosite database is deprecated in sing-box 1.8.0 and removed in sing-box 1.12.0",
		},
		{
			name: "LegacyRouteSourceGeoIP",
			config: `{
				"outbounds": [{"type": "direct", "tag": "direct"}],
				"route": {
					"rules": [{"source_geoip": ["cn"], "outbound": "direct"}]
				}
			}`,
			errContains: "geoip database is deprecated in sing-box 1.8.0 and removed in sing-box 1.12.0",
		},
		{
			name: "LegacyRuleSetIPCIDRAcceptEmpty",
			config: fmt.Sprintf(`{
				"outbounds": [{"type": "direct", "tag": "direct"}],
				"route": {
					"rule_set": [{"type": "local", "tag": "dummy", "format": "binary", "path": "%s"}]
				},
				"dns": {
					"servers": [{"type": "local", "tag": "dns-local", "detour": "direct"}],
					"rules": [
						{"rule_set": ["dummy"], "match_response": true, "rule_set_ip_cidr_accept_empty": true, "server": "dns-local"}
					]
				}
			}`, dummyPath),
			errContains: "Legacy `rule_set_ip_cidr_accept_empty` DNS rule item is deprecated in sing-box 1.14.0",
		},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			instance, err := NewSingBoxInstance(tc.config, nil)
			if err == nil {
				err = instance.Start()
				_ = instance.Close()
			}
			if err == nil {
				t.Fatalf("Config with legacy field must be rejected")
			}
			if !strings.Contains(err.Error(), tc.errContains) {
				t.Fatalf("Error message %q must contain %q", err.Error(), tc.errContains)
			}
		})
	}
}
