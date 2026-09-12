package compiler

import (
	"github.com/sagernet/sing-box/option"
	SJ "github.com/sagernet/sing/common/json"
	"strings"
	"testing"
)

func TestPortRangesRemainCompactAndBlockDNSProjection(t *testing.T) {
	m, safe, err := compileMatch(Match{Domains: []string{"example.com"}, PortRanges: []string{"1024:65535"}, SourcePortRanges: []string{":1023"}}, Platform{})
	if err != nil || safe {
		t.Fatalf("match=%+v safe=%v err=%v", m, safe, err)
	}
	if len(m.Port) != 0 || len(m.PortRange) != 1 || m.SourcePortRange[0] != "0:1023" {
		t.Fatalf("ranges expanded or lost: %+v", m)
	}
	b, err := SJ.Marshal(withMatch(m, option.RuleAction{Action: "reject"}))
	if err != nil || !strings.Contains(string(b), `"port_range":"1024:65535"`) {
		t.Fatalf("marshal=%s err=%v", b, err)
	}
}
func TestInvalidPortRangesRejected(t *testing.T) {
	for _, v := range []string{"10", "90:80", "1:65536", "-1:2", "1:2:3"} {
		if _, _, err := compileMatch(Match{PortRanges: []string{v}}, Platform{}); err == nil {
			t.Fatalf("accepted %q", v)
		}
	}
}
