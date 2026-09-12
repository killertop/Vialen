// Package compiler turns platform-independent intent into pinned sing-box options.
// Compilation performs no network, filesystem, database, or core lifecycle work.
package compiler

import (
	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
)

type Purpose string

const (
	Run    Purpose = "run"
	Probe  Purpose = "probe"
	Export Purpose = "export"
)

type Request struct {
	RawOutbounds []RawOutbound     `json:"raw_outbounds,omitempty"`
	Profiles     []profile.Profile `json:"profiles"`
	SelectedID   string            `json:"selected_id"`
	SelectorIDs  []string          `json:"selector_ids,omitempty"`
	RuleSets     []RuleSet         `json:"rule_sets,omitempty"`
	Chains       []Chain           `json:"chains,omitempty"`
	Policy       Policy            `json:"policy"`
	Purpose      Purpose           `json:"purpose"`
	Platform     Platform          `json:"platform"`
}

// Hops are ordered from the application-facing proxy to the network-facing
// exit. Entries may reference profiles or other chains; cycles are rejected.
type Chain struct {
	ID   string   `json:"id"`
	Hops []string `json:"hops"`
}

// RuleSet locations are supplied by the platform workflow. Compilation validates
// syntax and references but never opens paths or downloads remote data.
type DownloadRoute string

const (
	DownloadDirect   DownloadRoute = "direct"
	DownloadSelected DownloadRoute = "selected"
)

type RuleSet struct {
	ID             string        `json:"id"`
	Type           string        `json:"type"`   // local or remote
	Format         string        `json:"format"` // source or binary
	Path           string        `json:"path,omitempty"`
	URL            string        `json:"url,omitempty"`
	InitialPath    string        `json:"initial_path,omitempty"`
	DownloadDetour DownloadRoute `json:"download_detour,omitempty"` // direct default, or selected
}
type Candidate struct {
	ReferenceID string `json:"reference_id"`
	Tag         string `json:"tag"`
}
type RuleSetBinding struct {
	ID  string `json:"id"`
	Tag string `json:"tag"`
}
type Policy struct {
	ServerStrategy     string    `json:"server_strategy,omitempty"` // auto inherits IPv6 policy
	DNS                DNSPolicy `json:"dns"`
	IPv6               string    `json:"ipv6,omitempty"` // prefer_ipv4 (default), prefer_ipv6, ipv4_only, ipv6_only
	Sniff              bool      `json:"sniff,omitempty"`
	ResolveDestination bool      `json:"resolve_destination,omitempty"`
	BypassLAN          bool      `json:"bypass_lan,omitempty"`
	Rules              []Rule    `json:"rules,omitempty"`
}
type DNSPolicy struct {
	Direct       DNSServer `json:"direct"`
	Remote       DNSServer `json:"remote"`
	RouteDomains bool      `json:"route_domains,omitempty"`
	FakeIP       bool      `json:"fake_ip,omitempty"`
}

// DNS is structured; no overloaded URLs, fragments, or semicolon syntax.
type DNSServer struct {
	Strategy string `json:"strategy,omitempty"` // auto inherits IPv6 policy
	Type     string `json:"type"`
	Server   string `json:"server,omitempty"`
	Port     uint16 `json:"port,omitempty"`
	Path     string `json:"path,omitempty"`
}
type Platform struct {
	VPN              bool     `json:"vpn"`
	TUNAddresses     []string `json:"tun_addresses,omitempty"` // IP prefixes
	MTU              uint32   `json:"mtu,omitempty"`
	Stack            string   `json:"stack,omitempty"`      // system (default), gvisor, mixed
	MixedPort        uint16   `json:"mixed_port,omitempty"` // zero disables the local listener
	AllowLAN         bool     `json:"allow_lan,omitempty"`
	SupportsUIDRules bool     `json:"supports_uid_rules,omitempty"`
}
type Target struct {
	Kind string `json:"kind"`
	ID   string `json:"id,omitempty"`
} // selected, direct, reference
// An empty match is forbidden. Use route final for the default path.
type Rule struct {
	ID     string  `json:"id"`
	Match  Match   `json:"match"`
	Action string  `json:"action"`
	Target *Target `json:"target,omitempty"`
} // route or reject
// Different condition categories are ANDed by the core; entries within a
// category follow sing-box matching semantics. DNS projection is domain-only.
type Match struct {
	RuleSetIDs               []string `json:"rule_set_ids,omitempty"`
	RuleSetIPCIDRMatchSource bool     `json:"rule_set_ip_cidr_match_source,omitempty"`
	Domains                  []string `json:"domains,omitempty"`
	DomainSuffixes           []string `json:"domain_suffixes,omitempty"`
	DomainKeywords           []string `json:"domain_keywords,omitempty"`
	DomainRegexes            []string `json:"domain_regexes,omitempty"`
	IPCIDRs                  []string `json:"ip_cidrs,omitempty"`
	SourceIPCIDRs            []string `json:"source_ip_cidrs,omitempty"`
	IPIsPrivate              bool     `json:"ip_is_private,omitempty"`
	SourceIPIsPrivate        bool     `json:"source_ip_is_private,omitempty"`
	PortRanges               []string `json:"port_ranges,omitempty"`
	SourcePortRanges         []string `json:"source_port_ranges,omitempty"`
	Ports                    []uint16 `json:"ports,omitempty"`
	SourcePorts              []uint16 `json:"source_ports,omitempty"`
	Networks                 []string `json:"networks,omitempty"`
	Protocols                []string `json:"protocols,omitempty"`
	UIDs                     []uint32 `json:"uids,omitempty"`
}
type Binding struct {
	ReferenceID string `json:"reference_id"`
	ProfileID   string `json:"profile_id"`
	Tag         string `json:"tag"`
	Hop         int    `json:"hop"`
}
type Diagnostic struct {
	RuleID  string `json:"rule_id,omitempty"`
	Code    string `json:"code"`
	Message string `json:"message"`
}
type Metadata struct {
	SelectorCandidates []Candidate      `json:"selector_candidates"`
	RuleSets           []RuleSetBinding `json:"rule_sets,omitempty"`
	SelectedID         string           `json:"selected_id"`
	SelectedTag        string           `json:"selected_tag"`
	Bindings           []Binding        `json:"bindings"`
	Diagnostics        []Diagnostic     `json:"diagnostics,omitempty"`
}
type Plan struct {
	Options  option.Options `json:"-"`
	Config   string         `json:"config"`
	Metadata Metadata       `json:"metadata"`
}
