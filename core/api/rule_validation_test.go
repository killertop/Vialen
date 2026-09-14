package api

import "testing"

func TestRuleValidationBoundary(t *testing.T) {
	for _, input := range []string{`{"ports":[1,65535]}`, `{"port_ranges":[":443","1024:",":","0:65535"]}`, `{"source_port_ranges":[":443"]}`, `{"ip_is_private":true}`} {
		if _, err := Execute("validate_rule", []byte(input)); err != nil {
			t.Fatalf("valid match rejected: %s: %v", input, err)
		}
	}
	for _, input := range []string{`{}`, `{"ports":[0]}`, `{"ports":[65536]}`, `{"port_ranges":["2:1"]}`, `{"ip_cidrs":["192.0.2.0/99"]}`, `{"domain_regexes":["["]}`, `{"protocols":["unknown"]}`, `{"custom":{}}`} {
		if _, err := Execute("validate_rule", []byte(input)); err == nil {
			t.Fatalf("invalid match accepted: %s", input)
		}
	}
}
