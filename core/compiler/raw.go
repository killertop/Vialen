package compiler

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
	SJ "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/service"
)

// RawOutbound is a standalone sing-box dialer, not an overlay on a Profile.
// Tags and inter-object references are exclusively owned by the compiler.
type RawOutbound struct {
	ID   string          `json:"id"`
	JSON json.RawMessage `json:"json"`
}

type rawRegistry struct{}

func (rawRegistry) OptionTypes() []string {
	return []string{"socks", "http", "shadowsocks", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic", "anytls", "shadowtls", "ssh", "naive", "direct"}
}
func (rawRegistry) CreateOptions(kind string) (any, bool) {
	switch kind {
	case "socks":
		return &option.SOCKSOutboundOptions{}, true
	case "http":
		return &option.HTTPOutboundOptions{}, true
	case "shadowsocks":
		return &option.ShadowsocksOutboundOptions{}, true
	case "vmess":
		return &option.VMessOutboundOptions{}, true
	case "vless":
		return &option.VLESSOutboundOptions{}, true
	case "trojan":
		return &option.TrojanOutboundOptions{}, true
	case "hysteria":
		return &option.HysteriaOutboundOptions{}, true
	case "hysteria2":
		return &option.Hysteria2OutboundOptions{}, true
	case "tuic":
		return &option.TUICOutboundOptions{}, true
	case "anytls":
		return &option.AnyTLSOutboundOptions{}, true
	case "shadowtls":
		return &option.ShadowTLSOutboundOptions{}, true
	case "ssh":
		return &option.SSHOutboundOptions{}, true
	case "naive":
		return &option.NaiveOutboundOptions{}, true
	case "direct":
		return &option.DirectOutboundOptions{}, true
	default:
		return nil, false
	}
}
func decodeRaw(raw RawOutbound) (option.Outbound, error) {
	var out option.Outbound
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw.JSON, &fields); err != nil || fields == nil {
		return out, fmt.Errorf("raw outbound must be a JSON object")
	}
	for _, key := range []string{"tag", "detour", "domain_resolver", "domain_strategy", "netns"} {
		if _, ok := fields[key]; ok {
			return out, fmt.Errorf("raw outbound field %s is compiler-owned", key)
		}
	}
	ctx := service.ContextWith[option.OutboundOptionsRegistry](context.Background(), rawRegistry{})
	if err := SJ.UnmarshalContext(ctx, raw.JSON, &out); err != nil {
		return out, fmt.Errorf("invalid raw outbound: %w", err)
	}
	if _, ok := out.Options.(option.DialerOptionsWrapper); !ok {
		return out, fmt.Errorf("raw outbound must support dialer options")
	}
	if server, ok := out.Options.(option.ServerOptionsWrapper); ok {
		s := server.TakeServerOptions()
		normalized, err := profile.NormalizeServer(s.Server)
		if err != nil || s.ServerPort == 0 {
			return out, fmt.Errorf("raw outbound requires a valid server and port")
		}
		s.Server = normalized
		server.ReplaceServerOptions(s)
	}
	return out, nil
}
func rawIDValid(id string) bool { return strings.TrimSpace(id) != "" }
