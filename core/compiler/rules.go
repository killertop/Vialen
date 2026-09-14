package compiler

import (
	"fmt"
	"github.com/killertop/Vialen/core/profile"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"math"
	"net/netip"
	"regexp"
	"strconv"
	"strings"
)

func (p *planner) rules(r Request) error {
	seen := map[string]bool{}
	projectionOpen := true
	for _, rule := range r.Policy.Rules {
		if strings.TrimSpace(rule.ID) == "" || seen[rule.ID] {
			return fmt.Errorf("rule IDs must be nonempty and unique")
		}
		seen[rule.ID] = true
		match, safe, err := compileMatch(rule.Match, r.Platform)
		if err != nil {
			return fmt.Errorf("rule %s: %w", rule.ID, err)
		}
		for _, id := range rule.Match.RuleSetIDs {
			tag, ok := p.ruleSets[id]
			if !ok {
				return fmt.Errorf("missing rule-set reference")
			}
			match.RuleSet = append(match.RuleSet, tag)
		}
		action := option.RuleAction{Action: rule.Action}
		dnsAction := option.DNSRuleAction{Action: "reject"}
		switch rule.Action {
		case "reject":
			if rule.Target != nil {
				return fmt.Errorf("reject rule must not specify a target")
			}
		case "route":
			if rule.Target == nil {
				return fmt.Errorf("route rule requires a target")
			}
			tag := ""
			switch rule.Target.Kind {
			case "selected":
				tag = p.metadata.SelectedTag
				dnsAction = option.DNSRuleAction{Action: "route", RouteOptions: option.DNSRouteActionOptions{Server: "dns-remote", AbstractDNSRouteActionOptions: option.AbstractDNSRouteActionOptions{Strategy: p.remoteStrategy}}}
			case "direct":
				tag = "direct"
				dnsAction = option.DNSRuleAction{Action: "route", RouteOptions: option.DNSRouteActionOptions{Server: "dns-direct", AbstractDNSRouteActionOptions: option.AbstractDNSRouteActionOptions{Strategy: p.directStrategy}}}
			case "reference":
				tag, err = p.emit(rule.Target.ID)
				if err != nil {
					return err
				}
				safe = false
			default:
				return fmt.Errorf("invalid rule target kind")
			}
			if rule.Target.Kind != "reference" && rule.Target.ID != "" {
				return fmt.Errorf("only reference targets have an ID")
			}
			action.RouteOptions = option.RouteActionOptions{Outbound: tag}
		default:
			return fmt.Errorf("rule action must be route or reject")
		}
		p.options.Route.Rules = append(p.options.Route.Rules, withMatch(match, action))
		if !safe {
			projectionOpen = false
		}
		if r.Policy.DNS.RouteDomains {
			if !projectionOpen {
				p.metadata.Diagnostics = append(p.metadata.Diagnostics, Diagnostic{RuleID: rule.ID, Code: "dns_projection_skipped", Message: "DNS cannot reproduce this rule or preserve precedence after an earlier connection rule."})
				continue
			}
			query := option.RawDefaultDNSRule{Domain: append([]string(nil), match.Domain...), DomainSuffix: append([]string(nil), match.DomainSuffix...), DomainKeyword: append([]string(nil), match.DomainKeyword...), DomainRegex: append([]string(nil), match.DomainRegex...)}
			if r.Policy.DNS.FakeIP && rule.Target != nil && rule.Target.Kind == "selected" {
				fake := query
				fake.Inbound = []string{"tun-in"}
				fake.QueryType = []option.DNSQueryType{1, 28}
				p.options.DNS.Rules = append(p.options.DNS.Rules, dnsRule(fake, option.DNSRuleAction{Action: "route", RouteOptions: option.DNSRouteActionOptions{Server: "dns-fake"}}))
			}
			p.options.DNS.Rules = append(p.options.DNS.Rules, dnsRule(query, dnsAction))
		}
	}
	if r.Policy.DNS.FakeIP {
		p.options.DNS.Rules = append(p.options.DNS.Rules, dnsRule(option.RawDefaultDNSRule{Inbound: []string{"tun-in"}, QueryType: []option.DNSQueryType{1, 28}}, option.DNSRuleAction{Action: "route", RouteOptions: option.DNSRouteActionOptions{Server: "dns-fake"}}))
	}
	return nil
}

// ValidateRuleMatch uses exactly the same predicate checks as runtime compilation,
// without constructing a box, opening files, binding listeners or starting a VPN.
func ValidateRuleMatch(m Match) error {
	_, _, err := compileMatch(m, Platform{VPN: true, SupportsUIDRules: true})
	return err
}

func compileMatch(m Match, platform Platform) (option.RawDefaultRule, bool, error) {
	o := option.RawDefaultRule{IPIsPrivate: m.IPIsPrivate, SourceIPIsPrivate: m.SourceIPIsPrivate, RuleSetIPCIDRMatchSource: m.RuleSetIPCIDRMatchSource}
	if m.RuleSetIPCIDRMatchSource && len(m.RuleSetIDs) == 0 {
		return o, false, fmt.Errorf("source rule-set matching requires rule-set IDs")
	}
	domains := func(values []string) ([]string, error) {
		out := []string{}
		for _, v := range values {
			if strings.Contains(v, "://") {
				return nil, fmt.Errorf("domain conditions must not contain URLs")
			}
			host, e := profile.NormalizeServer(v)
			if e != nil {
				return nil, fmt.Errorf("invalid domain condition")
			}
			if _, e = netip.ParseAddr(host); e == nil {
				return nil, fmt.Errorf("IP literals require IP conditions")
			}
			out = append(out, host)
		}
		return out, nil
	}
	var err error
	if o.Domain, err = domains(m.Domains); err != nil {
		return o, false, err
	}
	if o.DomainSuffix, err = domains(m.DomainSuffixes); err != nil {
		return o, false, err
	}
	for _, v := range m.DomainKeywords {
		if strings.TrimSpace(v) == "" {
			return o, false, fmt.Errorf("domain keyword is empty")
		}
		o.DomainKeyword = append(o.DomainKeyword, strings.ToLower(v))
	}
	for _, v := range m.DomainRegexes {
		if v == "" {
			return o, false, fmt.Errorf("domain regex is empty")
		}
		if _, e := regexp.Compile(v); e != nil {
			return o, false, fmt.Errorf("invalid domain regex")
		}
		o.DomainRegex = append(o.DomainRegex, v)
	}
	for _, pair := range []struct {
		input  []string
		output *[]string
	}{{m.IPCIDRs, (*[]string)(&o.IPCIDR)}, {m.SourceIPCIDRs, (*[]string)(&o.SourceIPCIDR)}} {
		for _, v := range pair.input {
			if _, e := netip.ParsePrefix(v); e != nil {
				if a, e := netip.ParseAddr(v); e != nil || a.Zone() != "" {
					return o, false, fmt.Errorf("invalid IP or CIDR condition")
				}
			}
			*pair.output = append(*pair.output, v)
		}
	}
	for _, port := range append(append([]uint16{}, m.Ports...), m.SourcePorts...) {
		if port == 0 {
			return o, false, fmt.Errorf("port must be nonzero")
		}
	}
	for _, pair := range []struct {
		input  []string
		output *[]string
	}{{m.PortRanges, (*[]string)(&o.PortRange)}, {m.SourcePortRanges, (*[]string)(&o.SourcePortRange)}} {
		for _, value := range pair.input {
			bounds := strings.Split(value, ":")
			if len(bounds) != 2 {
				return o, false, fmt.Errorf("invalid port range")
			}
			parse := func(s string, fallback uint64) (uint64, error) {
				if s == "" {
					return fallback, nil
				}
				return strconv.ParseUint(s, 10, 16)
			}
			start, e1 := parse(bounds[0], 0)
			end, e2 := parse(bounds[1], 65535)
			if e1 != nil || e2 != nil || start > end {
				return o, false, fmt.Errorf("invalid port range")
			}
			*pair.output = append(*pair.output, fmt.Sprintf("%d:%d", start, end))
		}
	}
	o.Port = append([]uint16(nil), m.Ports...)
	o.SourcePort = append([]uint16(nil), m.SourcePorts...)
	for _, v := range m.Networks {
		if v != "tcp" && v != "udp" && v != "icmp" {
			return o, false, fmt.Errorf("invalid network condition")
		}
		o.Network = append(o.Network, v)
	}
	allowed := map[string]bool{"tls": true, "http": true, "quic": true, "dns": true, "stun": true, "bittorrent": true, "dtls": true, "ssh": true, "rdp": true, "ntp": true}
	for _, v := range m.Protocols {
		if !allowed[v] {
			return o, false, fmt.Errorf("invalid protocol condition")
		}
		o.Protocol = append(o.Protocol, v)
	}
	if len(m.UIDs) > 0 && (!platform.VPN || !platform.SupportsUIDRules) {
		return o, false, fmt.Errorf("UID rules require platform VPN UID support")
	}
	for _, v := range m.UIDs {
		if v > math.MaxInt32 {
			return o, false, fmt.Errorf("UID exceeds core range")
		}
		o.UserID = append(o.UserID, int32(v))
	}
	hasDomain := len(o.Domain)+len(o.DomainSuffix)+len(o.DomainKeyword)+len(o.DomainRegex) > 0
	hasOther := len(m.RuleSetIDs)+len(o.IPCIDR)+len(o.SourceIPCIDR)+len(o.Port)+len(o.SourcePort)+len(o.PortRange)+len(o.SourcePortRange)+len(o.Network)+len(o.Protocol)+len(o.UserID) > 0 || o.IPIsPrivate || o.SourceIPIsPrivate
	if !hasDomain && !hasOther {
		return o, false, fmt.Errorf("empty rule match would match all traffic")
	}
	return o, hasDomain && !hasOther, nil
}

// Domain and destination IP conditions are deliberately ANDed. A raw sing-box
// default rule groups destination conditions; make this product policy explicit.
func withMatch(m option.RawDefaultRule, a option.RuleAction) option.Rule {
	if len(m.RuleSet) > 0 {
		set := option.RawDefaultRule{RuleSet: m.RuleSet, RuleSetIPCIDRMatchSource: m.RuleSetIPCIDRMatchSource}
		m.RuleSet = nil
		m.RuleSetIPCIDRMatchSource = false
		hasOther := len(m.Domain)+len(m.DomainSuffix)+len(m.DomainKeyword)+len(m.DomainRegex)+len(m.IPCIDR)+len(m.SourceIPCIDR)+len(m.Port)+len(m.SourcePort)+len(m.PortRange)+len(m.SourcePortRange)+len(m.Network)+len(m.Protocol)+len(m.UserID) > 0 || m.IPIsPrivate || m.SourceIPIsPrivate
		if !hasOther {
			return routeRule(set, a)
		}
		return option.Rule{Type: C.RuleTypeLogical, LogicalOptions: option.LogicalRule{RawLogicalRule: option.RawLogicalRule{Mode: "and", Rules: []option.Rule{routeRule(set, option.RuleAction{}), withMatch(m, option.RuleAction{})}}, RuleAction: a}}
	}
	if len(m.Domain)+len(m.DomainSuffix)+len(m.DomainKeyword)+len(m.DomainRegex) > 0 && (len(m.IPCIDR) > 0 || m.IPIsPrivate) {
		d := option.RawDefaultRule{Domain: m.Domain, DomainSuffix: m.DomainSuffix, DomainKeyword: m.DomainKeyword, DomainRegex: m.DomainRegex}
		m.Domain = nil
		m.DomainSuffix = nil
		m.DomainKeyword = nil
		m.DomainRegex = nil
		return option.Rule{Type: C.RuleTypeLogical, LogicalOptions: option.LogicalRule{RawLogicalRule: option.RawLogicalRule{Mode: "and", Rules: []option.Rule{routeRule(d, option.RuleAction{}), routeRule(m, option.RuleAction{})}}, RuleAction: a}}
	}
	return routeRule(m, a)
}
