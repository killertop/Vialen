package libcore

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	R "github.com/sagernet/sing-box/route/rule"
	M "github.com/sagernet/sing/common/metadata"
	"golang.org/x/net/proxy"
)

// These configurations are independently regenerated and asserted by
// RuleSetModernizationTest through the actual database snapshot and Rust JNI.
func TestGeneratedNativeRouteSemantics(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "app", "src", "test", "resources", "native-route-semantics.json"))
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name   string         `json:"name"`
		Config map[string]any `json:"config"`
		Checks []struct {
			Domain      string `json:"domain"`
			Source      string `json:"source"`
			Destination string `json:"destination"`
			Network     string `json:"network"`
			Protocol    string `json:"protocol"`
			Want        string `json:"want"`
		} `json:"checks"`
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) < 19 {
		t.Fatal("missing generator fixtures")
	}
	for _, multi := range []bool{false, true} {
		variant := "single"
		if multi {
			variant = "multi"
		}
		for _, fixture := range cases {
			t.Run(variant+"/"+fixture.Name, func(t *testing.T) {
				var config map[string]any
				original, err := json.Marshal(fixture.Config)
				if err != nil {
					t.Fatal(err)
				}
				if err := json.Unmarshal(original, &config); err != nil {
					t.Fatal(err)
				}
				// No Android TUN or local listener is needed to exercise the real matcher.
				delete(config, "inbounds")
				route := config["route"].(map[string]any)
				if sets, ok := route["rule_set"].([]any); ok {
					for i, entry := range sets {
						set := entry.(map[string]any)
						url := set["url"].(string)
						var predicate option.DefaultHeadlessRule
						switch {
						case strings.HasSuffix(url, "/geosite-cn.srs"):
							predicate.Domain = []string{"cn.example"}
						case strings.HasSuffix(url, "/geoip-cn.srs"):
							predicate.IPCIDR = []string{"203.0.113.0/24"}
						case strings.HasSuffix(url, "/geoip-source.srs"):
							predicate.IPCIDR = []string{"192.168.10.0/24"}
						default:
							t.Fatalf("unmapped fixture set: %s", url)
						}
						rules := []option.HeadlessRule{{Type: C.RuleTypeDefault, DefaultOptions: predicate}}
						if multi {
							rules = append(rules, rules[0])
						}
						path := filepath.Join(t.TempDir(), "fixture.srs")
						file, err := os.Create(path)
						if err != nil {
							t.Fatal(err)
						}
						err = srs.Write(file, option.PlainRuleSet{Rules: rules}, C.RuleSetVersionCurrent)
						file.Close()
						if err != nil {
							t.Fatal(err)
						}
						sets[i] = map[string]any{"type": "local", "tag": set["tag"], "format": "binary", "path": path}
					}
				}
				encoded, err := json.Marshal(config)
				if err != nil {
					t.Fatal(err)
				}
				intfBox = &dummyPlatformInterface{}
				instance, err := NewSingBoxInstance(string(encoded), nil)
				if err != nil {
					t.Fatal(err)
				}
				defer instance.Close()
				if err = instance.Start(); err != nil {
					t.Fatal(err)
				}
				for i, check := range fixture.Checks {
					network := check.Network
					if network == "" {
						network = "tcp"
					}
					source := check.Source
					if source == "" {
						source = "192.0.2.1:1234"
					}
					metadata := adapter.InboundContext{Domain: check.Domain, Source: M.ParseSocksaddr(source), Destination: M.ParseSocksaddr(check.Destination), Network: network, Protocol: check.Protocol}
					actual := "proxy"
					for _, rule := range instance.Box.Router().Rules() {
						metadata.ResetRuleCache()
						if !rule.Match(&metadata) {
							continue
						}
						action := rule.Action()
						if !adapter.IsFinalAction(action) {
							continue
						}
						if route, ok := action.(*R.RuleActionRoute); ok {
							actual = route.Outbound
						} else {
							actual = action.Type()
						}
						break
					}
					if actual != check.Want {
						t.Errorf("case %d: domain=%q source=%s destination=%s got %s, want %s", i, check.Domain, source, check.Destination, actual, check.Want)
					}
				}
			})
		}
	}
}

// Real TCP connections distinguish bypass from the configured SOCKS outbound.
// The blocked target first succeeds through the baseline generated configuration.
func TestGeneratedNativeRouteConnections(t *testing.T) {
	markerServer := func(socks bool, counter *atomic.Int32) net.Listener {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { listener.Close() })
		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					return
				}
				go func() {
					defer conn.Close()
					conn.SetDeadline(time.Now().Add(5 * time.Second))
					marker := "DIRECT"
					if socks {
						header := make([]byte, 2)
						if _, err := io.ReadFull(conn, header); err != nil || header[0] != 5 {
							return
						}
						if _, err := io.CopyN(io.Discard, conn, int64(header[1])); err != nil {
							return
						}
						if _, err := conn.Write([]byte{5, 0}); err != nil {
							return
						}
						request := make([]byte, 4)
						if _, err := io.ReadFull(conn, request); err != nil || request[1] != 1 {
							return
						}
						size := int64(0)
						switch request[3] {
						case 1:
							size = 4
						case 4:
							size = 16
						case 3:
							n := make([]byte, 1)
							if _, err := io.ReadFull(conn, n); err != nil {
								return
							}
							size = int64(n[0])
						default:
							return
						}
						if _, err := io.CopyN(io.Discard, conn, size+2); err != nil {
							return
						}
						if _, err := conn.Write([]byte{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); err != nil {
							return
						}
						marker = "PROXY!"
					}
					counter.Add(1)
					_, _ = conn.Write([]byte(marker))
				}()
			}
		}()
		return listener
	}
	var directCount, proxyCount atomic.Int32
	origin := markerServer(false, &directCount)
	upstream := markerServer(true, &proxyCount)
	data, err := os.ReadFile(filepath.Join("..", "app", "src", "test", "resources", "native-route-semantics.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixtures []struct {
		Name   string         `json:"name"`
		Config map[string]any `json:"config"`
	}
	if err := json.Unmarshal(data, &fixtures); err != nil {
		t.Fatal(err)
	}
	exercised := 0
	for _, fixture := range fixtures {
		if fixture.Name != "transport-baseline" && fixture.Name != "transport-block" {
			continue
		}
		exercised++
		t.Run(fixture.Name, func(t *testing.T) {
			config := fixture.Config
			reservation, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			inboundPort := reservation.Addr().(*net.TCPAddr).Port
			reservation.Close()
			config["inbounds"] = []any{map[string]any{"type": "socks", "tag": "host-test", "listen": "127.0.0.1", "listen_port": inboundPort}}
			config["log"] = map[string]any{"disabled": true}
			for _, raw := range config["outbounds"].([]any) {
				out := raw.(map[string]any)
				if out["tag"] == "proxy" {
					out["server_port"] = upstream.Addr().(*net.TCPAddr).Port
				}
			}
			encoded, err := json.Marshal(config)
			if err != nil {
				t.Fatal(err)
			}
			intfBox = &dummyPlatformInterface{}
			instance, err := NewSingBoxInstance(string(encoded), nil)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
			dialer, err := proxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", inboundPort), nil, &net.Dialer{Timeout: 3 * time.Second})
			if err != nil {
				t.Fatal(err)
			}
			request := func(host string) (string, error) {
				conn, err := dialer.Dial("tcp", fmt.Sprintf("%s:%d", host, origin.Addr().(*net.TCPAddr).Port))
				if err != nil {
					return "", err
				}
				defer conn.Close()
				conn.SetDeadline(time.Now().Add(3 * time.Second))
				marker := make([]byte, 6)
				_, err = io.ReadFull(conn, marker)
				return string(marker), err
			}
			beforeDirect, beforeProxy := directCount.Load(), proxyCount.Load()
			if marker, err := request("127.0.0.1"); err != nil || marker != "DIRECT" {
				t.Fatalf("bypass: marker=%q err=%v", marker, err)
			}
			if directCount.Load() != beforeDirect+1 || proxyCount.Load() != beforeProxy {
				t.Fatal("bypass went through proxy")
			}
			if marker, err := request("198.51.100.1"); err != nil || marker != "PROXY!" {
				t.Fatalf("proxy: marker=%q err=%v", marker, err)
			}
			if proxyCount.Load() != beforeProxy+1 {
				t.Fatal("proxy outbound not reached")
			}
			beforeDirect, beforeProxy = directCount.Load(), proxyCount.Load()
			marker, err := request("198.51.100.2")
			if fixture.Name == "transport-baseline" {
				if err != nil || marker != "PROXY!" {
					t.Fatalf("block baseline was not reachable: %q %v", marker, err)
				}
			} else {
				if err == nil {
					t.Fatal("blocked connection succeeded")
				}
				if directCount.Load() != beforeDirect || proxyCount.Load() != beforeProxy {
					t.Fatal("blocked request reached an outbound")
				}
			}
		})
	}
	if exercised != 2 {
		t.Fatal("missing baseline/block transport fixtures")
	}
}

func TestValidateNativeRuleSet(t *testing.T) {
	for _, tc := range []struct {
		text, format string
		valid        bool
	}{
		{`{"version":4,"rules":[{"domain":["example.com"]}]}`, "source", true},
		{`{"version":4,"rules":[{"domain":["example.com"]}]} {}`, "source", false},
		{`{"version":4,"rules":[{"geoip":["cn"]}]}`, "source", false},
		{"not an SRS file", "binary", false},
		{"", "database", false},
	} {
		path := filepath.Join(t.TempDir(), "input")
		if err := os.WriteFile(path, []byte(tc.text), 0600); err != nil {
			t.Fatal(err)
		}
		if err := ValidateRuleSet(path, tc.format); (err == nil) != tc.valid {
			t.Errorf("format %s: err=%v, valid=%v", tc.format, err, tc.valid)
		}
	}
}
