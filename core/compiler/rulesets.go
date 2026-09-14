package compiler

import (
	"encoding/base64"
	"fmt"
	"github.com/killertop/Vialen/core/profile"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// ValidateRuleWithSets uses the runtime metadata and reference checks without
// loading rule files, downloading resources or constructing a running instance.
// A nil match supports metadata-only validation in the rule-set editor.
func ValidateRuleWithSets(match *Match, sets []RuleSet) error {
	if match == nil && len(sets) == 0 {
		return fmt.Errorf("rule validation input is empty")
	}
	r := Request{Purpose: Probe, RuleSets: sets}
	if match != nil {
		if err := ValidateRuleMatch(*match); err != nil {
			return err
		}
		r.Policy.Rules = []Rule{{Match: *match}}
	}
	p := planner{}
	return p.prepareRuleSets(r)
}

func (p *planner) prepareRuleSets(r Request) error {
	p.ruleSets = map[string]string{}
	for _, s := range r.RuleSets {
		if strings.TrimSpace(s.ID) == "" {
			return fmt.Errorf("rule-set ID is required")
		}
		if _, ok := p.ruleSets[s.ID]; ok {
			return fmt.Errorf("duplicate rule-set ID")
		}
		if s.Format != "source" && s.Format != "binary" {
			return fmt.Errorf("rule-set format must be source or binary")
		}
		tag := "rule-set-" + base64.RawURLEncoding.EncodeToString([]byte(s.ID))
		o := option.RuleSet{Type: s.Type, Tag: []string{tag}, Format: s.Format}
		switch s.Type {
		case "local":
			if s.URL != "" || s.InitialPath != "" || s.DownloadDetour != "" {
				return fmt.Errorf("local rule-set cannot have remote options")
			}
			if !validLocalPath(s.Path) || !matchingFormat(s.Path, s.Format) {
				return fmt.Errorf("invalid local rule-set path or format")
			}
			o.LocalOptions = option.LocalRuleSet{Path: s.Path}
		case "remote":
			if s.Path != "" {
				return fmt.Errorf("remote rule-set cannot have a local path")
			}
			if s.DownloadDetour != "" && s.DownloadDetour != DownloadDirect && s.DownloadDetour != DownloadSelected {
				return fmt.Errorf("invalid rule-set download route")
			}
			u, err := url.Parse(s.URL)
			if err != nil || u.Scheme != "https" || u.Host == "" || strings.HasSuffix(u.Host, ":") || u.User != nil || u.Fragment != "" || strings.IndexFunc(s.URL, unicode.IsSpace) >= 0 {
				return fmt.Errorf("remote rule-set requires an HTTPS URL without credentials or fragment")
			}
			if _, err = profile.NormalizeServer(u.Hostname()); err != nil {
				return fmt.Errorf("invalid remote rule-set host")
			}
			if port := u.Port(); port != "" {
				n, err := strconv.ParseUint(port, 10, 16)
				if err != nil || n == 0 {
					return fmt.Errorf("invalid remote rule-set port")
				}
			}
			if u.Path == "" || strings.HasSuffix(u.Path, "/") || !matchingFormat(u.Path, s.Format) {
				return fmt.Errorf("invalid remote rule-set path or format")
			}
			if s.InitialPath != "" && (!validLocalPath(s.InitialPath) || !matchingFormat(s.InitialPath, s.Format)) {
				return fmt.Errorf("invalid rule-set initial path")
			}
			detour := ""
			if s.DownloadDetour == DownloadSelected {
				detour = p.metadata.SelectedTag
			}
			client := &option.HTTPClientOptions{DialerOptions: option.DialerOptions{Detour: detour, DomainResolver: &option.DomainResolveOptions{Server: "dns-direct"}}}
			o.RemoteOptions = option.RemoteRuleSet{URL: s.URL, InitialPath: s.InitialPath, HTTPClient: client, UpdateInterval: badoption.Duration(24 * time.Hour)}
		default:
			return fmt.Errorf("rule-set type must be local or remote")
		}
		p.ruleSets[s.ID] = tag
		if r.Purpose != Probe {
			p.options.Route.RuleSet = append(p.options.Route.RuleSet, o)
			p.metadata.RuleSets = append(p.metadata.RuleSets, RuleSetBinding{ID: s.ID, Tag: tag})
		}
	}
	// Validate references even when probe intentionally omits all rule-set loading.
	for _, rule := range r.Policy.Rules {
		seen := map[string]bool{}
		for _, id := range rule.Match.RuleSetIDs {
			if _, ok := p.ruleSets[id]; !ok {
				return fmt.Errorf("missing rule-set reference")
			}
			if seen[id] {
				return fmt.Errorf("duplicate rule-set reference")
			}
			seen[id] = true
		}
		if rule.Match.RuleSetIPCIDRMatchSource && len(seen) == 0 {
			return fmt.Errorf("source rule-set matching requires rule-set IDs")
		}
	}
	return nil
}
func validLocalPath(v string) bool {
	return v != "" && path.IsAbs(v) && path.Clean(v) == v && v != "/" && !strings.HasSuffix(v, "/") && strings.IndexFunc(v, func(r rune) bool { return unicode.IsControl(r) }) < 0 && !strings.Contains(v, "\\")
}
func matchingFormat(v, format string) bool {
	switch strings.ToLower(path.Ext(v)) {
	case ".db":
		return false
	case ".json":
		return format == "source"
	case ".srs":
		return format == "binary"
	}
	return true
}
