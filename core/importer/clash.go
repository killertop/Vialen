package importer

import (
	"encoding/json"
	"github.com/killertop/Vialen/core/profile"
	"math"
	"strconv"
	"strings"
)

func parseClash(text string) (Result, error) {
	out := result("clash")
	v, e := parseYAML(text)
	if e != nil {
		return out, e
	}
	root, e := object(v)
	if e != nil {
		return out, bad("INVALID_CLASH", "Expected a Clash mapping")
	}
	list, ok := root["proxies"].([]any)
	if !ok {
		return out, bad("INVALID_CLASH", "Expected a proxies array")
	}
	if e = sourceLimit(len(list)); e != nil {
		return out, e
	}
	rootFields := fields{m: root}
	global := rootFields.str("global-client-fingerprint")
	if rootFields.err != nil {
		return out, rootFields.err
	}
	for i, v := range list {
		p, e := clashProfile(v, global)
		out.add(i, p, e)
	}
	return out, nil
}
func clashProfile(v any, global string) (profile.Profile, error) {
	m, e := object(v)
	if e != nil {
		return profile.Profile{}, e
	}
	f := fields{m: m}
	if f.has("dialer-proxy") {
		dependency := f.str("dialer-proxy")
		if f.err != nil {
			return profile.Profile{}, f.err
		}
		if dependency != "" {
			if strings.TrimSpace(dependency) == "" {
				return profile.Profile{}, fieldError("dialer-proxy")
			}
			return profile.Profile{}, bad("DEPENDENT_NODE", "Proxy dependencies require an explicit chain")
		}
	}
	// These constraints cannot be represented by a standalone profile. Do not
	// silently turn a constrained connection into an unconstrained one.
	for _, key := range []string{"interface-name", "fingerprint", "certificate", "private-key", "certificate-path", "private-key-path"} {
		if key == "private-key" && strings.EqualFold(f.str("type"), "wireguard") {
			continue
		}
		if f.has(key) {
			value := f.str(key)
			if f.err != nil {
				return profile.Profile{}, f.err
			}
			if value != "" {
				return profile.Profile{}, bad("UNSUPPORTED_SECURITY_FIELD", "Unsupported connection constraint: "+key)
			}
		}
	}
	if f.has("routing-mark") {
		mark := f.uint("routing-mark", math.MaxUint32)
		if f.err != nil {
			return profile.Profile{}, f.err
		}
		if mark != 0 {
			return profile.Profile{}, bad("UNSUPPORTED_SECURITY_FIELD", "Unsupported connection constraint: routing-mark")
		}
	}
	ty := strings.ToLower(f.str("type"))
	p := profile.Profile{Name: f.str("name"), Server: f.str("server"), Port: uint16(f.uint("port", 65535))}
	switch ty {
	case "socks5", "socks4", "socks4a":
		p.Type = "socks"
		p.Socks = &profile.Socks{Version: strings.TrimPrefix(ty, "socks"), Username: f.str("username"), Password: f.str("password")}
	case "http":
		p.Type = "http"
		p.HTTP = &profile.HTTP{Username: f.str("username"), Password: f.str("password")}
	case "ss":
		p.Type = "shadowsocks"
		p.Shadowsocks = &profile.Shadowsocks{Method: f.str("cipher"), Password: f.str("password"), Plugin: f.str("plugin")}
		if f.has("plugin-opts") {
			opts := f.child("plugin-opts")
			plugin := p.Shadowsocks.Plugin
			switch plugin {
			case "obfs":
				p.Shadowsocks.Plugin = "obfs-local"
				p.Shadowsocks.PluginOptions = "obfs=" + opts.str("mode") + ";obfs-host=" + opts.str("host")
			case "v2ray-plugin":
				parts := []string{}
				if s := opts.str("mode"); s != "" {
					parts = append(parts, "mode="+s)
				}
				if opts.boolean("tls") {
					parts = append(parts, "tls")
				}
				if s := opts.str("host"); s != "" {
					parts = append(parts, "host="+s)
				}
				if s := opts.str("path"); s != "" {
					parts = append(parts, "path="+s)
				}
				if opts.boolean("mux") {
					parts = append(parts, "mux=8")
				}
				p.Shadowsocks.PluginOptions = strings.Join(parts, ";")
			default:
				opts.err = bad("UNSUPPORTED_PLUGIN", "Unsupported Shadowsocks plugin options")
			}
			f.accept(opts)
		}
	case "vmess":
		p.Type = "vmess"
		p.VMess = &profile.VMess{UUID: f.str("uuid"), Security: f.str("cipher"), AlterID: uint32(f.uint("alterId", math.MaxUint32)), PacketEncoding: f.str("packet-encoding")}
	case "vless":
		p.Type = "vless"
		p.VLESS = &profile.VLESS{UUID: f.str("uuid"), Flow: f.str("flow"), PacketEncoding: f.str("packet-encoding")}
	case "trojan":
		p.Type = "trojan"
		p.Trojan = &profile.Trojan{Password: f.str("password")}
	case "hysteria2", "hy2":
		p.Type = "hysteria2"
		hy := &profile.Hysteria2{Password: f.str("password"), UpMbps: clashMbps(&f, "up"), DownMbps: clashMbps(&f, "down")}
		if f.has("ports") {
			hy.ServerPorts = parsePortRanges(f.str("ports"))
		}
		if s := f.str("obfs"); s != "" {
			hy.Obfs = &profile.Obfs{Type: s, Password: f.str("obfs-password")}
		}
		p.Hysteria2 = hy
	case "tuic":
		p.Type = "tuic"
		p.TUIC = &profile.TUIC{UUID: f.str("uuid"), Password: f.str("password"), CongestionControl: f.str("congestion-controller"), UDPRelayMode: f.str("udp-relay-mode"), ZeroRTTHandshake: f.boolean("reduce-rtt")}
	case "shadowtls":
		p.Type = "shadowtls"
		p.ShadowTLS = &profile.ShadowTLS{Version: uint32(f.uint("version", 3)), Password: f.str("password")}
	case "anytls":
		p.Type = "anytls"
		p.AnyTLS = &profile.AnyTLS{Password: f.str("password")}
	case "wireguard":
		p.Type = "wireguard"
		wg := &profile.WireGuard{PrivateKey: f.str("private-key"), PublicKey: f.str("public-key"), PreSharedKey: f.str("pre-shared-key"), MTU: uint32(f.uint("mtu", math.MaxUint32)), PersistentKeepalive: uint32(f.uint("persistent-keepalive", 65535)), AllowedIPs: f.strings("allowed-ips")}
		for _, k := range []string{"ip", "ipv6"} {
			if s := f.str(k); s != "" {
				if !strings.Contains(s, "/") {
					if k == "ip" {
						s += "/32"
					} else {
						s += "/128"
					}
				}
				wg.Address = append(wg.Address, s)
			}
		}
		wg.Reserved = reserved(&f, "reserved")
		p.WireGuard = wg
	default:
		return p, bad("UNSUPPORTED_PROTOCOL", "Unsupported Clash proxy type")
	}
	advancedFields(&f, &p, true)
	if p.Type != "wireguard" {
		tls := &profile.TLS{DisableSNI: f.boolean("disable-sni"), Enabled: f.boolean("tls"), ServerName: f.str("sni"), Insecure: f.boolean("skip-cert-verify"), ALPN: f.strings("alpn"), Fingerprint: f.str("client-fingerprint")}
		if tls.ServerName == "" {
			tls.ServerName = f.str("servername")
		}
		if tls.Fingerprint == "" {
			tls.Fingerprint = global
		}
		if p.Type == "shadowtls" || p.Type == "trojan" || p.Type == "hysteria2" || p.Type == "tuic" || p.Type == "anytls" {
			tls.Enabled = true
		}
		if f.has("reality-opts") {
			r := f.child("reality-opts")
			tls.Reality = &profile.Reality{PublicKey: r.str("public-key"), ShortID: r.str("short-id")}
			tls.Enabled = true
			f.accept(r)
		}
		if f.has("ech-opts") {
			e := f.child("ech-opts")
			tls.ECH = &profile.ECH{Enabled: e.boolean("enable"), Config: listable(e, "config")}
			f.accept(e)
		}
		if tls.Enabled {
			p.TLS = tls
		}
		transport, e := clashTransport(&f)
		if e != nil {
			return p, e
		}
		p.Transport = transport
	}
	if f.err != nil {
		return p, f.err
	}
	server, e := profile.NormalizeServer(p.Server)
	if e != nil {
		return p, bad("INVALID_SERVER", "Invalid proxy server")
	}
	p.Server = server
	return p, nil
}
func clashMbps(f *fields, k string) uint32 {
	if !f.has(k) {
		return 0
	}
	if _, ok := f.m[k].(json.Number); ok {
		return uint32(f.uint(k, math.MaxUint32))
	}
	s, ok := f.m[k].(string)
	if !ok {
		f.err = fieldError(k)
		return 0
	}
	s = strings.TrimSpace(s)
	if strings.HasSuffix(strings.ToLower(s), "mbps") {
		s = strings.TrimSpace(s[:len(s)-4])
	}
	n, e := strconv.ParseUint(s, 10, 32)
	if e != nil {
		f.err = fieldError(k)
		return 0
	}
	return uint32(n)
}
func parsePortRanges(s string) []string {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	items := strings.Split(s, ",")
	for i := range items {
		items[i] = strings.ReplaceAll(strings.TrimSpace(items[i]), "-", ":")
	}
	return items
}
func reserved(f *fields, k string) []uint32 {
	if !f.has(k) {
		return nil
	}
	a, ok := f.m[k].([]any)
	if !ok {
		f.err = fieldError(k)
		return nil
	}
	out := []uint32{}
	for _, v := range a {
		entry := fields{m: map[string]any{"value": v}}
		out = append(out, uint32(entry.uint("value", 255)))
		if entry.err != nil {
			f.err = fieldError(k)
		}
	}
	return out
}
func clashTransport(f *fields) (*profile.Transport, error) {
	network := f.str("network")
	if network == "" || network == "tcp" {
		return nil, f.err
	}
	t := &profile.Transport{Type: network}
	switch network {
	case "ws":
		opts := f.child("ws-opts")
		t.Path = opts.str("path")
		t.MaxEarlyData = uint32(opts.uint("max-early-data", math.MaxUint32))
		t.EarlyDataHeaderName = opts.str("early-data-header-name")
		if opts.boolean("v2ray-http-upgrade") {
			t.Type = "httpupgrade"
		}
		if opts.has("headers") {
			h := opts.child("headers")
			for k := range h.m {
				if strings.EqualFold(k, "host") {
					t.Host = []string{h.str(k)}
				} else {
					if t.Headers == nil {
						t.Headers = map[string][]string{}
					}
					t.Headers[k] = []string{h.str(k)}
				}
			}
			opts.accept(h)
		}
		f.accept(opts)
	case "h2":
		t.Type = "http"
		opts := f.child("h2-opts")
		t.Host = opts.strings("host")
		t.Path = opts.str("path")
		f.accept(opts)
	case "http":
		opts := f.child("http-opts")
		paths := opts.strings("path")
		if len(paths) > 1 {
			return nil, bad("UNSUPPORTED_FIELD", "Multiple HTTP transport paths are not supported")
		}
		if len(paths) == 1 {
			t.Path = paths[0]
		}
		if opts.has("headers") {
			h := opts.child("headers")
			for k := range h.m {
				if strings.EqualFold(k, "host") {
					t.Host = h.strings(k)
				} else {
					if t.Headers == nil {
						t.Headers = map[string][]string{}
					}
					t.Headers[k] = h.strings(k)
				}
			}
			opts.accept(h)
		}
		f.accept(opts)
	case "grpc":
		opts := f.child("grpc-opts")
		t.ServiceName = opts.str("grpc-service-name")
		f.accept(opts)
	case "httpupgrade":
		opts := f.child("http-upgrade-opts")
		t.Path = opts.str("path")
		if host := opts.str("host"); host != "" {
			t.Host = []string{host}
		}
		f.accept(opts)
	default:
		return nil, bad("UNSUPPORTED_TRANSPORT", "Unsupported transport type")
	}
	return t, f.err
}
