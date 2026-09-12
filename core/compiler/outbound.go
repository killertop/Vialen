package compiler

import (
	"fmt"
	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"net/netip"
	"strings"
	"time"
)

func tlsOptions(t *profile.TLS) option.OutboundTLSOptionsContainer {
	if t == nil {
		return option.OutboundTLSOptionsContainer{}
	}
	o := &option.OutboundTLSOptions{Enabled: t.Enabled, DisableSNI: t.DisableSNI, ServerName: t.ServerName, Insecure: t.Insecure, ALPN: append([]string(nil), t.ALPN...)}
	if t.ECH != nil {
		o.ECH = &option.OutboundECHOptions{Enabled: t.ECH.Enabled, Config: append([]string(nil), t.ECH.Config...), QueryServerName: t.ECH.QueryServerName}
	}
	if t.Certificate != "" {
		o.Certificate = []string{t.Certificate}
	}
	fingerprint := t.Fingerprint
	if fingerprint == "" && t.Reality != nil {
		// Reality requires uTLS even when the share link leaves its fingerprint implicit.
		fingerprint = "chrome"
	}
	if fingerprint != "" {
		o.UTLS = &option.OutboundUTLSOptions{Enabled: true, Fingerprint: fingerprint}
	}
	if t.Reality != nil {
		o.Reality = &option.OutboundRealityOptions{Enabled: true, PublicKey: t.Reality.PublicKey, ShortID: t.Reality.ShortID}
	}
	return option.OutboundTLSOptionsContainer{TLS: o}
}
func transportOptions(t *profile.Transport) *option.V2RayTransportOptions {
	if t == nil || t.Type == "tcp" {
		return nil
	}
	o := &option.V2RayTransportOptions{Type: t.Type}
	switch t.Type {
	case "ws":
		headers := transportHeaders(t.Headers)
		if len(t.Host) > 0 {
			headers["Host"] = append([]string(nil), t.Host...)
		}
		o.WebsocketOptions = option.V2RayWebsocketOptions{Path: t.Path, Headers: headers, MaxEarlyData: t.MaxEarlyData, EarlyDataHeaderName: t.EarlyDataHeaderName}
	case "http":
		o.HTTPOptions = option.V2RayHTTPOptions{Host: append([]string(nil), t.Host...), Path: t.Path, Headers: transportHeaders(t.Headers)}
	case "grpc":
		o.GRPCOptions = option.V2RayGRPCOptions{ServiceName: t.ServiceName}
	case "httpupgrade":
		o.HTTPUpgradeOptions = option.V2RayHTTPUpgradeOptions{Host: strings.Join(t.Host, ","), Path: t.Path, Headers: transportHeaders(t.Headers)}
	}
	return o
}
func outboundOptions(p profile.Profile, detour string) (any, error) {
	d := option.DialerOptions{Detour: detour, DomainResolver: &option.DomainResolveOptions{Server: "dns-direct"}}
	server, _ := profile.NormalizeServer(p.Server)
	s := option.ServerOptions{Server: server, ServerPort: p.Port}
	tls := tlsOptions(p.TLS)
	transport := transportOptions(p.Transport)
	switch p.Type {
	case "socks":
		v := p.Socks
		return &option.SOCKSOutboundOptions{DialerOptions: d, ServerOptions: s, Version: v.Version, Username: v.Username, Password: v.Password, UDPOverTCP: uotOptions(p.UDPOverTCP)}, nil
	case "http":
		v := p.HTTP
		return &option.HTTPOutboundOptions{DialerOptions: d, ServerOptions: s, Username: v.Username, Password: v.Password, OutboundTLSOptionsContainer: tls}, nil
	case "shadowsocks":
		v := p.Shadowsocks
		return &option.ShadowsocksOutboundOptions{DialerOptions: d, ServerOptions: s, Method: v.Method, Password: v.Password, Plugin: v.Plugin, PluginOptions: v.PluginOptions, UDPOverTCP: uotOptions(p.UDPOverTCP), Multiplex: muxOptions(p.Multiplex)}, nil
	case "vmess":
		v := p.VMess
		security := v.Security
		if security == "" {
			security = "auto"
		}
		return &option.VMessOutboundOptions{DialerOptions: d, ServerOptions: s, UUID: v.UUID, Security: security, AlterId: int(v.AlterID), OutboundTLSOptionsContainer: tls, Transport: transport, PacketEncoding: v.PacketEncoding, Multiplex: muxOptions(p.Multiplex)}, nil
	case "vless":
		v := p.VLESS
		return &option.VLESSOutboundOptions{DialerOptions: d, ServerOptions: s, UUID: v.UUID, Flow: v.Flow, OutboundTLSOptionsContainer: tls, Transport: transport, PacketEncoding: optionalString(v.PacketEncoding), Multiplex: muxOptions(p.Multiplex)}, nil
	case "trojan":
		return &option.TrojanOutboundOptions{DialerOptions: d, ServerOptions: s, Password: p.Trojan.Password, OutboundTLSOptionsContainer: tls, Transport: transport, Multiplex: muxOptions(p.Multiplex)}, nil
	case "hysteria2":
		v := p.Hysteria2
		o := &option.Hysteria2OutboundOptions{DialerOptions: d, ServerOptions: s, Password: v.Password, HopInterval: badoption.Duration(time.Duration(v.HopInterval) * time.Second), HopIntervalMax: badoption.Duration(time.Duration(v.HopIntervalMax) * time.Second), BBRProfile: v.BBRProfile, DisableChromeParrot: v.DisableChromeParrot, UpMbps: int(v.UpMbps), DownMbps: int(v.DownMbps), ServerPorts: append([]string(nil), v.ServerPorts...), OutboundTLSOptionsContainer: tls}
		if v.Obfs != nil {
			o.Obfs = &option.Hysteria2Obfs{Type: v.Obfs.Type, Password: v.Obfs.Password, GeckoOptions: option.Hysteria2ObfsGecko{MinPacketSize: int(v.Obfs.MinPacketSize), MaxPacketSize: int(v.Obfs.MaxPacketSize)}}
		}
		return o, nil
	case "tuic":
		v := p.TUIC
		return &option.TUICOutboundOptions{DialerOptions: d, ServerOptions: s, UUID: v.UUID, Password: v.Password, CongestionControl: v.CongestionControl, UDPRelayMode: v.UDPRelayMode, ZeroRTTHandshake: v.ZeroRTTHandshake, OutboundTLSOptionsContainer: tls}, nil
	case "shadowtls":
		return &option.ShadowTLSOutboundOptions{DialerOptions: d, ServerOptions: s, Version: int(p.ShadowTLS.Version), Password: p.ShadowTLS.Password, OutboundTLSOptionsContainer: tls}, nil
	case "anytls":
		return &option.AnyTLSOutboundOptions{DialerOptions: d, ServerOptions: s, Password: p.AnyTLS.Password, OutboundTLSOptionsContainer: tls}, nil
	case "wireguard":
		v := p.WireGuard
		address, err := prefixes(v.Address)
		if err != nil {
			return nil, err
		}
		allowed, err := prefixes(v.AllowedIPs)
		if err != nil {
			return nil, err
		}
		if len(allowed) == 0 {
			allowed = []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0")}
		}
		if v.PersistentKeepalive > 65535 {
			return nil, fmt.Errorf("WireGuard keepalive exceeds 65535")
		}
		return &option.WireGuardEndpointOptions{DialerOptions: d, MTU: v.MTU, Address: address, PrivateKey: v.PrivateKey, Peers: []option.WireGuardPeer{{Address: server, Port: p.Port, PublicKey: v.PublicKey, PreSharedKey: v.PreSharedKey, AllowedIPs: allowed, Reserved: reservedBytes(v.Reserved), PersistentKeepaliveInterval: uint16(v.PersistentKeepalive)}}}, nil
	}
	return nil, fmt.Errorf("unsupported protocol %q", p.Type)
}
func prefixes(values []string) ([]netip.Prefix, error) {
	out := make([]netip.Prefix, 0, len(values))
	for _, v := range values {
		p, e := netip.ParsePrefix(v)
		if e != nil {
			return nil, fmt.Errorf("invalid IP prefix")
		}
		out = append(out, p)
	}
	return out, nil
}

func reservedBytes(values []uint32) []uint8 {
	out := make([]uint8, len(values))
	for i, v := range values {
		out[i] = uint8(v)
	}
	return out
}
func optionalString(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
func transportHeaders(values map[string][]string) badoption.HTTPHeader {
	out := badoption.HTTPHeader{}
	for k, v := range values {
		out[k] = append([]string(nil), v...)
	}
	return out
}

func uotOptions(v *profile.UDPOverTCP) *option.UDPOverTCPOptions {
	if v == nil {
		return nil
	}
	return &option.UDPOverTCPOptions{Enabled: v.Enabled, Version: uint8(v.Version)}
}
func muxOptions(v *profile.Multiplex) *option.OutboundMultiplexOptions {
	if v == nil {
		return nil
	}
	return &option.OutboundMultiplexOptions{Enabled: v.Enabled, Protocol: v.Protocol, MaxConnections: int(v.MaxConnections), MinStreams: int(v.MinStreams), MaxStreams: int(v.MaxStreams), Padding: v.Padding}
}
