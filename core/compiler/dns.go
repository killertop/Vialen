package compiler

import (
	"fmt"
	"github.com/killertop/Vialen/core/profile"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"net/netip"
	"strings"
)

func compileDNS(r Request, selected string, strategy option.DomainStrategy) (*option.DNSOptions, error) {
	directStrategy, err := selectedStrategy(r.Policy.DNS.Direct.Strategy, strategy)
	if err != nil {
		return nil, err
	}
	remoteStrategy, err := selectedStrategy(r.Policy.DNS.Remote.Strategy, strategy)
	if err != nil {
		return nil, err
	}
	direct, err := dnsServer(r.Policy.DNS.Direct, "dns-direct", "dns-local", "", directStrategy)
	if err != nil {
		return nil, fmt.Errorf("direct DNS: %w", err)
	}
	o := &option.DNSOptions{RawDNSOptions: option.RawDNSOptions{Servers: []option.DNSServerOptions{{Type: "local", Tag: "dns-local", Options: &option.LocalDNSServerOptions{}}, direct}, Final: "dns-direct", DNSClientOptions: option.DNSClientOptions{Strategy: directStrategy}}}
	if r.Purpose == Probe {
		return o, nil
	}
	remote, err := dnsServer(r.Policy.DNS.Remote, "dns-remote", "dns-direct", selected, directStrategy)
	if err != nil {
		return nil, fmt.Errorf("remote DNS: %w", err)
	}
	o.Servers = append(o.Servers, remote)
	o.Final = "dns-remote"
	o.Strategy = remoteStrategy
	if r.Policy.DNS.FakeIP {
		if !r.Platform.VPN {
			return nil, fmt.Errorf("fake IP DNS requires VPN")
		}
		v4, v6 := badoption.Prefix(netip.MustParsePrefix("198.18.0.0/15")), badoption.Prefix(netip.MustParsePrefix("fc00::/18"))
		o.Servers = append(o.Servers, option.DNSServerOptions{Type: "fakeip", Tag: "dns-fake", Options: &option.FakeIPDNSServerOptions{Inet4Range: &v4, Inet6Range: &v6}})
	}
	return o, nil
}
func dnsServer(s DNSServer, tag, resolver, detour string, bootstrap ...option.DomainStrategy) (option.DNSServerOptions, error) {
	o := option.DNSServerOptions{Type: s.Type, Tag: tag}
	if s.Type == "local" {
		if s.Server != "" || s.Port != 0 || s.Path != "" {
			return o, fmt.Errorf("local DNS has no server, port or path")
		}
		o.Options = &option.LocalDNSServerOptions{}
		return o, nil
	}
	server, err := profile.NormalizeServer(s.Server)
	if err != nil {
		return o, fmt.Errorf("DNS server must be a valid host or IP, not a URL")
	}
	s.Server = server
	d := option.DialerOptions{Detour: detour, DomainResolver: &option.DomainResolveOptions{Server: resolver}}
	if len(bootstrap) > 0 {
		d.DomainResolver.Strategy = bootstrap[0]
	}
	base := option.RemoteDNSServerOptions{RawLocalDNSServerOptions: option.RawLocalDNSServerOptions{DialerOptions: d}, DNSServerAddressOptions: option.DNSServerAddressOptions{Server: s.Server, ServerPort: s.Port}}
	if s.Type != "https" && s.Type != "h3" && s.Path != "" {
		return o, fmt.Errorf("DNS path is only valid for HTTPS")
	}
	switch s.Type {
	case "udp", "tcp":
		o.Options = &base
	case "tls", "quic":
		o.Options = &option.RemoteTLSDNSServerOptions{RemoteDNSServerOptions: base}
	case "https", "h3":
		path := s.Path
		if path == "" {
			path = "/dns-query"
		}
		if !strings.HasPrefix(path, "/") || strings.ContainsAny(path, "?#\r\n") {
			return o, fmt.Errorf("invalid DNS path")
		}
		o.Options = &option.RemoteHTTPSDNSServerOptions{RemoteTLSDNSServerOptions: option.RemoteTLSDNSServerOptions{RemoteDNSServerOptions: base}, Path: path}
	default:
		return o, fmt.Errorf("unsupported DNS transport")
	}
	return o, nil
}
func dnsRule(match option.RawDefaultDNSRule, action option.DNSRuleAction) option.DNSRule {
	return option.DNSRule{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultDNSRule{RawDefaultDNSRule: match, DNSRuleAction: action}}
}
