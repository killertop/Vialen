package compiler

import (
	"context"
	"github.com/sagernet/sing-box/option"
	SJ "github.com/sagernet/sing/common/json"
	"strings"
	"testing"
)

func selectorOptions(t *testing.T, p Plan) *option.SelectorOutboundOptions {
	t.Helper()
	for _, o := range p.Options.Outbounds {
		if o.Tag == p.Metadata.SelectedTag {
			v, ok := o.Options.(*option.SelectorOutboundOptions)
			if !ok {
				t.Fatal("selected tag is not a selector")
			}
			return v
		}
	}
	t.Fatal("selector missing")
	return nil
}
func TestSelectorCandidateOrderDefaultAndGraphReferences(t *testing.T) {
	r := request()
	b := r.Profiles[0]
	b.ID = "b"
	r.Profiles = append(r.Profiles, b)
	r.Chains = []Chain{{ID: "chain", Hops: []string{"a", "b"}}}
	r.SelectorIDs = []string{"chain", "b", "a"}
	r.SelectedID = "b"
	p := compile(t, r)
	s := selectorOptions(t, p)
	if p.Metadata.SelectedTag != "selected" || len(s.Outbounds) != 3 || len(p.Metadata.SelectorCandidates) != 3 {
		t.Fatal("selector metadata missing")
	}
	if len(p.Metadata.Bindings) != 4 {
		t.Fatal("every candidate hop needs a statistics binding")
	}
	tags := map[string]bool{}
	for _, o := range p.Options.Outbounds {
		if tags[o.Tag] {
			t.Fatal("duplicate outbound tag")
		}
		tags[o.Tag] = true
	}
	for i, v := range s.Outbounds {
		if !tags[v] || p.Metadata.SelectorCandidates[i].ReferenceID != r.SelectorIDs[i] || p.Metadata.SelectorCandidates[i].Tag != v {
			t.Fatal("selector references or order changed")
		}
	}
	if s.Default != p.Metadata.SelectorCandidates[1].Tag {
		t.Fatal("default must identify selected candidate")
	}
	r.SelectorIDs = nil
	p = compile(t, r)
	if len(selectorOptions(t, p).Outbounds) != 1 || p.Metadata.SelectorCandidates[0].ReferenceID != "b" {
		t.Fatal("empty candidates must default to selected ID")
	}
	for _, ids := range [][]string{{"a", "a"}, {"missing"}, {"a"}, {""}} {
		r.SelectorIDs = ids
		if _, e := Compile(r); e == nil {
			t.Fatalf("accepted invalid selector %v", ids)
		}
	}
}
func TestTypedRuleSetsAndDNSProjection(t *testing.T) {
	r := request()
	r.RuleSets = []RuleSet{{ID: "local", Type: "local", Format: "source", Path: "/data/app/rules/local.json"}, {ID: "remote", Type: "remote", Format: "binary", URL: "https://rules.example/country.srs", InitialPath: "/data/app/rules/country.srs"}}
	r.Policy.Rules = []Rule{{ID: "source-set", Match: Match{RuleSetIDs: []string{"local"}, RuleSetIPCIDRMatchSource: true}, Action: "route", Target: &Target{Kind: "direct"}}, {ID: "destination-set", Match: Match{Domains: []string{"example.com"}, RuleSetIDs: []string{"remote"}}, Action: "reject"}, {ID: "later-domain", Match: Match{Domains: []string{"example.com"}}, Action: "reject"}}
	p := compile(t, r)
	if len(p.Options.Route.RuleSet) != 2 || len(p.Metadata.RuleSets) != 2 {
		t.Fatal("rule-set definitions missing")
	}
	remote := p.Options.Route.RuleSet[1].RemoteOptions
	if remote.HTTPClient == nil || remote.HTTPClient.Detour != "" || remote.HTTPClient.DomainResolver.Server != "dns-direct" || remote.DownloadDetour != "" {
		t.Fatal("default rule-set download must use the modern direct HTTP client")
	}
	if remote.InitialPath != r.RuleSets[1].InitialPath {
		t.Fatal("bootstrap path lost")
	}
	if len(p.Metadata.Diagnostics) != 3 || len(p.Options.DNS.Rules) != 0 {
		t.Fatal("rule sets cannot safely project to DNS or permit later shadowing")
	}
	rules := p.Options.Route.Rules
	source := rules[2].DefaultOptions
	if !source.RuleSetIPCIDRMatchSource || source.RuleSet[0] != p.Metadata.RuleSets[0].Tag {
		t.Fatal("source-set reference lost")
	}
	destination := rules[3]
	if destination.Type != "logical" || destination.LogicalOptions.Mode != "and" {
		t.Fatal("form conditions must AND with rule sets")
	}
	if rules[4].DefaultOptions.Domain[0] != "example.com" {
		t.Fatal("user rule order changed")
	}
	raw, e := SJ.MarshalContext(context.Background(), &p.Options)
	if e != nil {
		t.Fatal(e)
	}
	if strings.Contains(string(raw), "download_detour") || !strings.Contains(string(raw), "http_client") {
		t.Fatal("must marshal modern rule-set download options")
	}
	r.RuleSets[1].DownloadDetour = DownloadSelected
	p = compile(t, r)
	if p.Options.Route.RuleSet[1].RemoteOptions.HTTPClient.Detour != p.Metadata.SelectedTag {
		t.Fatal("selected download route missing")
	}
}
func TestRuleSetValidationFailsClosed(t *testing.T) {
	base := RuleSet{ID: "set", Type: "local", Format: "binary", Path: "/data/rules/country.srs"}
	cases := []RuleSet{{ID: "", Type: "local", Format: "binary", Path: "/a.srs"}, {ID: "set", Type: "local", Format: "invalid", Path: "/a.srs"}, {ID: "set", Type: "local", Format: "source", Path: "/a.srs"}, {ID: "set", Type: "local", Format: "binary", Path: "relative.srs"}, {ID: "set", Type: "local", Format: "binary", Path: "/data/../a.srs"}, {ID: "set", Type: "local", Format: "binary", Path: "/data/a.db"}, {ID: "set", Type: "remote", Format: "binary", URL: "http://a/a.srs"}, {ID: "set", Type: "remote", Format: "binary", URL: "https://user:secret@a/a.srs"}, {ID: "set", Type: "remote", Format: "source", URL: "https://a/a.srs"}, {ID: "set", Type: "remote", Format: "binary", URL: "https://a/a.srs", DownloadDetour: "unknown"}, {ID: "set", Type: "remote", Format: "binary", URL: "https://a/a.srs", InitialPath: "relative.srs"}}
	for i, s := range cases {
		r := request()
		r.RuleSets = []RuleSet{s}
		if _, e := Compile(r); e == nil {
			t.Fatalf("accepted invalid definition %d", i)
		}
	}
	r := request()
	r.RuleSets = []RuleSet{base, base}
	if _, e := Compile(r); e == nil {
		t.Fatal("duplicate definition accepted")
	}
	r.RuleSets = []RuleSet{base}
	for _, ids := range [][]string{{"missing"}, {"set", "set"}} {
		r.Policy.Rules = []Rule{{ID: "rule", Action: "reject", Match: Match{RuleSetIDs: ids}}}
		if _, e := Compile(r); e == nil {
			t.Fatal("invalid references accepted")
		}
	}
	r.Policy.Rules = []Rule{{ID: "source", Action: "reject", Match: Match{Domains: []string{"a.example"}, RuleSetIPCIDRMatchSource: true}}}
	if _, e := Compile(r); e == nil {
		t.Fatal("source flag without sets accepted")
	}
}
func TestProbeOmitsRemoteRuleSetLoading(t *testing.T) {
	r := request()
	r.Purpose = Probe
	r.RuleSets = []RuleSet{{ID: "set", Type: "remote", Format: "binary", URL: "https://rules.example/a.srs"}}
	r.Policy.Rules = []Rule{{ID: "rule", Match: Match{RuleSetIDs: []string{"set"}}, Action: "reject"}}
	p := compile(t, r)
	if len(p.Options.Route.RuleSet) != 0 || len(p.Options.Route.Rules) != 0 {
		t.Fatal("probe must not load unrelated rule sets")
	}
	r.Policy.Rules[0].Match.RuleSetIDs = []string{"missing"}
	if _, e := Compile(r); e == nil {
		t.Fatal("probe must still validate reference closure")
	}
}
