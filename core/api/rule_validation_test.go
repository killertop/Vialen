package api

import (
	"encoding/json"
	"testing"
)

func TestRuleSetMetadataValidationBoundary(t *testing.T) {
	for _, tc := range []struct {
		url, format string
		valid       bool
	}{
		{"https://example.invalid/rules.json", "binary", false},
		{"https://example.invalid:65536/rules.srs", "binary", false},
		{"https://example.invalid:0/rules.srs", "binary", false},
		{"https://example.invalid", "binary", false},
		{"https://example.invalid/rules.srs", "binary", true},
		{"https://example.invalid/rules.json", "source", true},
	} {
		for _, editorOnly := range []bool{true, false} {
			request := map[string]any{"rule_sets": []any{map[string]any{"id": "set-0", "type": "remote", "url": tc.url, "format": tc.format}}}
			if !editorOnly {
				request["match"] = map[string]any{"rule_set_ids": []string{"set-0"}}
			}
			input, _ := json.Marshal(request)
			_, err := Execute("validate_rule", input)
			if (err == nil) != tc.valid {
				t.Fatalf("url=%s format=%s editor=%v: %v", tc.url, tc.format, editorOnly, err)
			}
		}
	}
	for _, input := range []string{
		`{"match":{"rule_set_ids":["missing"]},"rule_sets":[]}`,
		`{"rule_sets":[{"id":"local","type":"local","path":"/rules.json","format":"binary"}]}`,
		`{"rule_sets":[{"id":"local","type":"local","path":"/rules.srs","format":"source"}]}`,
		`{}`,
	} {
		if _, err := Execute("validate_rule", []byte(input)); err == nil {
			t.Fatalf("invalid metadata accepted: %s", input)
		}
	}
}

func TestRuleValidationBoundary(t *testing.T) {
	for _, input := range []string{`{"ports":[1,65535]}`, `{"port_ranges":[":443","1024:",":","0:65535"]}`, `{"source_port_ranges":[":443"]}`, `{"ip_is_private":true}`} {
		if _, err := Execute("validate_rule", []byte(`{"match":`+input+`}`)); err != nil {
			t.Fatalf("valid match rejected: %s: %v", input, err)
		}
	}
	for _, input := range []string{`{}`, `{"ports":[0]}`, `{"ports":[65536]}`, `{"port_ranges":["2:1"]}`, `{"ip_cidrs":["192.0.2.0/99"]}`, `{"domain_regexes":["["]}`, `{"protocols":["unknown"]}`, `{"custom":{}}`} {
		if _, err := Execute("validate_rule", []byte(`{"match":`+input+`}`)); err == nil {
			t.Fatalf("invalid match accepted: %s", input)
		}
	}
}
