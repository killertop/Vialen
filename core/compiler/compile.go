package compiler

import (
	"context"
	"encoding/base64"
	"fmt"
	"github.com/killertop/Vialen/core/profile"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	SJ "github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/json/badoption"
	"net/netip"
	"strings"
)

const maxExpandedHops = 512

type graph struct {
	raw      map[string]RawOutbound
	profiles map[string]profile.Profile
	chains   map[string]Chain
	expanded map[string][]string
}

func newGraph(r Request) (*graph, error) {
	g := &graph{profiles: map[string]profile.Profile{}, raw: map[string]RawOutbound{}, chains: map[string]Chain{}, expanded: map[string][]string{}}
	for _, p := range r.Profiles {
		if strings.TrimSpace(p.ID) == "" {
			return nil, fmt.Errorf("profile ID is required")
		}
		if _, exists := g.profiles[p.ID]; exists {
			return nil, fmt.Errorf("duplicate profile ID")
		}
		if err := profile.Validate(p); err != nil {
			return nil, fmt.Errorf("profile %s: %w", p.ID, err)
		}
		g.profiles[p.ID] = p
	}
	for _, raw := range r.RawOutbounds {
		if !rawIDValid(raw.ID) {
			return nil, fmt.Errorf("raw outbound ID is required")
		}
		if _, ok := g.raw[raw.ID]; ok {
			return nil, fmt.Errorf("duplicate raw outbound ID")
		}
		if _, ok := g.profiles[raw.ID]; ok {
			return nil, fmt.Errorf("profile and raw outbound IDs overlap")
		}
		if _, err := decodeRaw(raw); err != nil {
			return nil, fmt.Errorf("raw outbound %s: %w", raw.ID, err)
		}
		g.raw[raw.ID] = raw
	}
	for _, c := range r.Chains {
		if strings.TrimSpace(c.ID) == "" {
			return nil, fmt.Errorf("chain ID is required")
		}
		if _, exists := g.chains[c.ID]; exists {
			return nil, fmt.Errorf("duplicate chain ID")
		}
		if _, exists := g.raw[c.ID]; exists {
			return nil, fmt.Errorf("chain and raw outbound IDs overlap")
		}
		if _, exists := g.profiles[c.ID]; exists {
			return nil, fmt.Errorf("profile and chain IDs overlap")
		}
		g.chains[c.ID] = c
	}
	for _, c := range r.Chains {
		if _, err := g.expand(c.ID, map[string]bool{}); err != nil {
			return nil, err
		}
	}
	for _, rule := range r.Policy.Rules {
		if rule.Target != nil && rule.Target.Kind == "reference" {
			if _, err := g.expand(rule.Target.ID, map[string]bool{}); err != nil {
				return nil, err
			}
		}
	}
	return g, nil
}
func (g *graph) expand(id string, path map[string]bool) ([]string, error) {
	if _, ok := g.raw[id]; ok {
		return []string{id}, nil
	}
	if _, ok := g.profiles[id]; ok {
		return []string{id}, nil
	}
	if path[id] {
		return nil, fmt.Errorf("cyclic chain reference")
	}
	if v, ok := g.expanded[id]; ok {
		return v, nil
	}
	chain, ok := g.chains[id]
	if !ok {
		return nil, fmt.Errorf("missing profile or chain reference: %s", id)
	}
	if len(chain.Hops) == 0 {
		return nil, fmt.Errorf("chain must contain at least one hop")
	}
	if len(path) >= maxExpandedHops {
		return nil, fmt.Errorf("chain nesting exceeds limit")
	}
	path[id] = true
	defer delete(path, id)
	out := []string{}
	for _, child := range chain.Hops {
		part, err := g.expand(child, path)
		if err != nil {
			return nil, err
		}
		if len(out)+len(part) > maxExpandedHops {
			return nil, fmt.Errorf("expanded chain exceeds hop limit")
		}
		out = append(out, part...)
	}
	g.expanded[id] = out
	return out, nil
}

type planner struct {
	serverStrategy option.DomainStrategy
	directStrategy option.DomainStrategy
	remoteStrategy option.DomainStrategy
	g              *graph
	options        option.Options
	metadata       Metadata
	references     map[string]string
	ruleSets       map[string]string
}

func (p *planner) emit(id string) (string, error) {
	if tag, ok := p.references[id]; ok {
		return tag, nil
	}
	hops, err := p.g.expand(id, map[string]bool{})
	if err != nil {
		return "", err
	}
	tags := make([]string, len(hops))
	for i := range tags {
		tags[i] = fmt.Sprintf("node-%s-%d", base64.RawURLEncoding.EncodeToString([]byte(id)), i)
	}
	for i, pid := range hops {
		detour := ""
		if i+1 < len(tags) {
			detour = tags[i+1]
		}
		if raw, ok := p.g.raw[pid]; ok {
			outbound, err := decodeRaw(raw)
			if err != nil {
				return "", err
			}
			dialer := outbound.Options.(option.DialerOptionsWrapper)
			d := dialer.TakeDialerOptions()
			d.Detour = detour
			d.DomainResolver = &option.DomainResolveOptions{Server: "dns-direct", Strategy: p.serverStrategy}
			dialer.ReplaceDialerOptions(d)
			outbound.Tag = tags[i]
			p.options.Outbounds = append(p.options.Outbounds, outbound)
			p.metadata.Bindings = append(p.metadata.Bindings, Binding{ReferenceID: id, ProfileID: pid, Tag: tags[i], Hop: i})
			continue
		}
		profile := p.g.profiles[pid]
		o, err := outboundOptions(profile, detour)
		if err != nil {
			return "", err
		}
		if dialer, ok := o.(option.DialerOptionsWrapper); ok {
			d := dialer.TakeDialerOptions()
			d.DomainResolver = &option.DomainResolveOptions{Server: "dns-direct", Strategy: p.serverStrategy}
			dialer.ReplaceDialerOptions(d)
		}
		if profile.Type == "wireguard" {
			p.options.Endpoints = append(p.options.Endpoints, option.Endpoint{Type: profile.Type, Tag: tags[i], Options: o})
		} else {
			p.options.Outbounds = append(p.options.Outbounds, option.Outbound{Type: profile.Type, Tag: tags[i], Options: o})
		}
		p.metadata.Bindings = append(p.metadata.Bindings, Binding{ReferenceID: id, ProfileID: pid, Tag: tags[i], Hop: i})
	}
	p.references[id] = tags[0]
	return tags[0], nil
}

// Compile validates a closed profile graph, then constructs typed sing-box
// options. Background context is sufficient for marshaling concrete option
// objects; registry-backed unmarshaling/runtime validation belongs to the adapter.
func Compile(r Request) (Plan, error) {
	if r.Purpose != Run && r.Purpose != Probe && r.Purpose != Export {
		return Plan{}, fmt.Errorf("purpose must be run, probe or export")
	}
	g, err := newGraph(r)
	if err != nil {
		return Plan{}, err
	}
	strategy, err := domainStrategy(r.Policy.IPv6)
	if err != nil {
		return Plan{}, err
	}
	p := planner{g: g, references: map[string]string{}, metadata: Metadata{SelectedID: r.SelectedID, Bindings: []Binding{}}, options: option.Options{Log: &option.LogOptions{Level: "warn"}}}
	// Android owns the persisted selected reference. Core selector cache would
	// override this request's explicit default on a later start.
	p.options.Experimental = &option.ExperimentalOptions{CacheFile: &option.CacheFileOptions{Enabled: false}}
	p.serverStrategy, err = selectedStrategy(r.Policy.ServerStrategy, strategy)
	if err != nil {
		return Plan{}, err
	}
	p.directStrategy, err = selectedStrategy(r.Policy.DNS.Direct.Strategy, strategy)
	if err != nil {
		return Plan{}, err
	}
	p.remoteStrategy, err = selectedStrategy(r.Policy.DNS.Remote.Strategy, strategy)
	if err != nil {
		return Plan{}, err
	}
	selectedTag, err := p.selector(r)
	if err != nil {
		return Plan{}, err
	}
	p.metadata.SelectedTag = selectedTag
	p.options.Outbounds = append(p.options.Outbounds, option.Outbound{Type: "direct", Tag: "direct", Options: &option.DirectOutboundOptions{DialerOptions: option.DialerOptions{DomainResolver: &option.DomainResolveOptions{Server: "dns-direct", Strategy: p.directStrategy}}}})
	p.options.Route = &option.RouteOptions{Final: selectedTag, AutoDetectInterface: true, DefaultDomainResolver: &option.DomainResolveOptions{Server: "dns-direct", Strategy: p.directStrategy}}
	dns, err := compileDNS(r, selectedTag, strategy)
	if err != nil {
		return Plan{}, err
	}
	p.options.DNS = dns
	if err = p.prepareRuleSets(r); err != nil {
		return Plan{}, err
	}
	if r.Purpose != Probe {
		if err = p.inbounds(r.Platform); err != nil {
			return Plan{}, err
		}
		if r.Policy.ResolveDestination {
			p.options.Route.Rules = append(p.options.Route.Rules, routeRule(option.RawDefaultRule{}, option.RuleAction{Action: "resolve", ResolveOptions: option.RouteActionResolve{Strategy: strategy}}))
		}
		if r.Policy.Sniff {
			p.options.Route.Rules = append(p.options.Route.Rules, routeRule(option.RawDefaultRule{}, option.RuleAction{Action: "sniff"}))
		}
		p.options.Route.Rules = append(p.options.Route.Rules, routeRule(option.RawDefaultRule{Port: []uint16{53}}, option.RuleAction{Action: "hijack-dns"}), routeRule(option.RawDefaultRule{Protocol: []string{"dns"}}, option.RuleAction{Action: "hijack-dns"}))
		if err = p.rules(r); err != nil {
			return Plan{}, err
		}
		if r.Policy.BypassLAN {
			p.options.Route.Rules = append(p.options.Route.Rules, routeRule(option.RawDefaultRule{IPIsPrivate: true}, option.RuleAction{Action: "route", RouteOptions: option.RouteActionOptions{Outbound: "direct"}}))
		}
	}
	raw, err := SJ.MarshalContext(context.Background(), &p.options)
	if err != nil {
		return Plan{}, fmt.Errorf("serialize core options: %w", err)
	}
	return Plan{Options: p.options, Config: string(raw), Metadata: p.metadata}, nil
}
func domainStrategy(raw string) (option.DomainStrategy, error) {
	switch raw {
	case "", "prefer_ipv4":
		return option.DomainStrategy(C.DomainStrategyPreferIPv4), nil
	case "prefer_ipv6":
		return option.DomainStrategy(C.DomainStrategyPreferIPv6), nil
	case "ipv4_only":
		return option.DomainStrategy(C.DomainStrategyIPv4Only), nil
	case "ipv6_only":
		return option.DomainStrategy(C.DomainStrategyIPv6Only), nil
	}
	return 0, fmt.Errorf("invalid IPv6 policy")
}
func (p *planner) inbounds(platform Platform) error {
	if platform.VPN {
		addresses, err := prefixes(platform.TUNAddresses)
		if err != nil || len(addresses) == 0 {
			return fmt.Errorf("VPN requires valid TUN prefixes")
		}
		// Platform addresses describe the local packet/DNS entry points. They
		// must not be removed by the independent upstream DNS address strategy.
		mtu := platform.MTU
		if mtu == 0 {
			mtu = 1500
		}
		if mtu < 1280 || mtu > 65535 {
			return fmt.Errorf("TUN MTU must be 1280..65535")
		}
		stack := platform.Stack
		if stack == "" {
			stack = "system"
		}
		if stack != "system" && stack != "gvisor" && stack != "mixed" {
			return fmt.Errorf("invalid TUN stack")
		}
		p.options.Inbounds = append(p.options.Inbounds, option.Inbound{Type: "tun", Tag: "tun-in", Options: &option.TunInboundOptions{Address: addresses, MTU: mtu, Stack: stack}})
	}
	if platform.MixedPort != 0 {
		address := netip.MustParseAddr("127.0.0.1")
		if platform.AllowLAN {
			address = netip.IPv4Unspecified()
		}
		listen := badoption.Addr(address)
		p.options.Inbounds = append(p.options.Inbounds, option.Inbound{Type: "mixed", Tag: "mixed-in", Options: &option.HTTPMixedInboundOptions{ListenOptions: option.ListenOptions{Listen: &listen, ListenPort: platform.MixedPort}}})
	}
	if !platform.VPN && platform.MixedPort == 0 {
		return fmt.Errorf("run/export requires a VPN or mixed listener")
	}
	return nil
}
func routeRule(match option.RawDefaultRule, action option.RuleAction) option.Rule {
	return option.Rule{Type: C.RuleTypeDefault, DefaultOptions: option.DefaultRule{RawDefaultRule: match, RuleAction: action}}
}

func (p *planner) selector(r Request) (string, error) {
	ids := r.SelectorIDs
	if len(ids) == 0 {
		ids = []string{r.SelectedID}
	}
	seen := map[string]bool{}
	tags := []string{}
	defaultTag := ""
	for _, id := range ids {
		if strings.TrimSpace(id) == "" || seen[id] {
			return "", fmt.Errorf("selector candidates must be nonempty and unique")
		}
		seen[id] = true
		tag, err := p.emit(id)
		if err != nil {
			return "", err
		}
		tags = append(tags, tag)
		p.metadata.SelectorCandidates = append(p.metadata.SelectorCandidates, Candidate{ReferenceID: id, Tag: tag})
		if id == r.SelectedID {
			defaultTag = tag
		}
	}
	if defaultTag == "" {
		return "", fmt.Errorf("selected ID must be a selector candidate")
	}
	p.options.Outbounds = append(p.options.Outbounds, option.Outbound{Type: "selector", Tag: "selected", Options: &option.SelectorOutboundOptions{Outbounds: tags, Default: defaultTag}})
	return "selected", nil
}

func selectedStrategy(value string, fallback option.DomainStrategy) (option.DomainStrategy, error) {
	if value == "" || value == "auto" {
		return fallback, nil
	}
	return domainStrategy(value)
}
