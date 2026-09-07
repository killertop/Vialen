package libcore

import (
	"strings"
	"testing"
)

func TestCustomConfigCases(t *testing.T) {
	// Case 1: Legacy address = "local" (in 1.14 typed DNS servers are required)
	// Expected: REJECTED_WITH_CLEAR_REASON
	t.Run("Case1_LegacyDnsLocalAddress", func(t *testing.T) {
		json := `{
			"dns": {
				"servers": [{"tag": "dns-local", "address": "local"}]
			},
			"outbounds": [{"type": "direct", "tag": "direct"}]
		}`
		_, err := NewSingBoxInstance(json, nil)
		if err == nil {
			t.Fatalf("Expected rejection for legacy DNS address string in 1.14, got nil")
		}
		errMsg := err.Error()
		if !strings.Contains(errMsg, "legacy DNS server formats") || !strings.Contains(errMsg, "removed in sing-box 1.14.0") {
			t.Fatalf("Expected clear legacy DNS deprecation/removal error, got: %v", errMsg)
		}
	})

	// Case 2: Legacy address = "https://1.1.1.1/dns-query"
	// Expected: REJECTED_WITH_CLEAR_REASON
	t.Run("Case2_LegacyDnsUrlForm", func(t *testing.T) {
		json := `{
			"dns": {
				"servers": [{"tag": "dns-doh", "address": "https://1.1.1.1/dns-query"}]
			},
			"outbounds": [{"type": "direct", "tag": "direct"}]
		}`
		_, err := NewSingBoxInstance(json, nil)
		if err == nil {
			t.Fatalf("Expected rejection for legacy DNS URL form in 1.14, got nil")
		}
		errMsg := err.Error()
		if !strings.Contains(errMsg, "legacy DNS server formats") || !strings.Contains(errMsg, "removed in sing-box 1.14.0") {
			t.Fatalf("Expected clear legacy DNS URL deprecation/removal error, got: %v", errMsg)
		}
	})

	// Case 3: Legacy TUN: inet4_address
	// Expected: REJECTED_WITH_CLEAR_REASON
	t.Run("Case3_LegacyTunInet4Address", func(t *testing.T) {
		json := `{
			"inbounds": [{"type": "tun", "tag": "tun-in", "inet4_address": "172.19.0.1/28"}],
			"outbounds": [{"type": "direct", "tag": "direct"}]
		}`
		_, err := NewSingBoxInstance(json, nil)
		if err == nil {
			t.Fatalf("Expected rejection for legacy tun inet4_address in 1.14, got nil")
		}
		errMsg := err.Error()
		if !strings.Contains(errMsg, "legacy tun address fields are deprecated") {
			t.Fatalf("Expected clear legacy tun address deprecation/removal error, got: %v", errMsg)
		}
	})

	// Case 4: Legacy inbound: sniff / domain_strategy
	// Expected: REJECTED_WITH_CLEAR_REASON
	t.Run("Case4_LegacyInboundSniffAndDomainStrategy", func(t *testing.T) {
		json := `{
			"inbounds": [{"type": "socks", "tag": "socks-in", "listen": "127.0.0.1", "listen_port": 1080, "sniff": true, "domain_strategy": "prefer_ipv4"}],
			"outbounds": [{"type": "direct", "tag": "direct"}]
		}`
		_, err := NewSingBoxInstance(json, nil)
		if err == nil {
			t.Fatalf("Expected rejection for legacy inbound sniff/domain_strategy in 1.14, got nil")
		}
		errMsg := err.Error()
		if !strings.Contains(errMsg, "legacy inbound fields are deprecated") {
			t.Fatalf("Expected clear legacy inbound fields deprecation/removal error, got: %v", errMsg)
		}
	})

	// Case 5: Legacy WireGuard outbound (in 1.14 wireguard is an endpoint)
	// Expected: REJECTED_WITH_CLEAR_REASON
	t.Run("Case5_LegacyWireGuardOutbound", func(t *testing.T) {
		json := `{
			"outbounds": [{"type": "wireguard", "tag": "wg-out", "server": "127.0.0.1", "server_port": 51820}]
		}`
		_, err := NewSingBoxInstance(json, nil)
		if err == nil {
			t.Fatalf("Expected rejection for legacy wireguard outbound type, got nil")
		}
		errMsg := err.Error()
		if !strings.Contains(errMsg, "unknown outbound type: wireguard") {
			t.Fatalf("Expected 'unknown outbound type: wireguard', got: %v", errMsg)
		}
	})

	// Case 6a: Special outbound type = block
	// Expected: STILL_VALID_IN_EXACT_1_14
	t.Run("Case6a_BlockOutbound_StillValidIn1_14", func(t *testing.T) {
		jsonBlock := `{
			"outbounds": [{"type": "block", "tag": "block-out"}]
		}`
		instance, errBlock := NewSingBoxInstance(jsonBlock, nil)
		if errBlock != nil {
			t.Fatalf("Expected block outbound to be valid in 1.14, got error: %v", errBlock)
		}
		instance.Close()
	})

	// Case 6b: Special outbound type = dns
	// Expected: REJECTED_WITH_CLEAR_REASON
	t.Run("Case6b_DnsOutbound_RejectedIn1_14", func(t *testing.T) {
		jsonDns := `{
			"outbounds": [{"type": "dns", "tag": "dns-out"}]
		}`
		_, errDns := NewSingBoxInstance(jsonDns, nil)
		if errDns == nil {
			t.Fatalf("Expected rejection for legacy dns outbound, got nil")
		}
		if !strings.Contains(errDns.Error(), "dns outbound is deprecated") {
			t.Fatalf("Expected 'dns outbound is deprecated', got: %v", errDns.Error())
		}
	})
}
