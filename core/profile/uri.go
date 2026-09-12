package profile

import (
	"net/url"
	"strconv"
	"strings"
	"unicode/utf8"
)

const MaxURIBytes = 1024 * 1024

// ParseURI imports a supported share URI. It does not guess deprecated encodings
// after a recognized format fails, and never includes credentials in errors.
func ParseURI(raw string) (Profile, error) {
	if len(raw) > MaxURIBytes {
		return Profile{}, invalid("uri", "too_large")
	}
	if !utf8.ValidString(raw) || strings.ContainsAny(raw, "\r\n\x00") {
		return Profile{}, invalid("uri", "invalid_text")
	}
	raw = strings.TrimSpace(raw)
	scheme, payload, hasScheme := strings.Cut(raw, "://")
	if hasScheme && strings.EqualFold(scheme, "vmess") && !strings.Contains(payload, "@") {
		p, e := parseVMessJSON(payload)
		if e != nil {
			return Profile{}, e
		}
		return checked(p)
	}
	u, e := url.Parse(raw)
	if e != nil || u.Opaque != "" || u.Host == "" {
		return Profile{}, invalid("uri", "invalid_url")
	}
	u.Scheme = strings.ToLower(u.Scheme)
	q, e := url.ParseQuery(u.RawQuery)
	if e != nil {
		return Profile{}, invalid("query", "invalid_encoding")
	}
	for _, values := range q {
		if len(values) != 1 {
			return Profile{}, invalid("query", "duplicate_parameter")
		}
	}
	server, e := NormalizeServer(u.Hostname())
	if e != nil {
		return Profile{}, e
	}
	p := Profile{Name: u.Fragment, Server: server}
	if u.Port() != "" {
		p.Port, e = port(u.Port())
		if e != nil {
			return Profile{}, e
		}
	} else if strings.HasSuffix(u.Host, ":") {
		return Profile{}, invalid("port", "required")
	}
	user, password := "", ""
	hasPassword := false
	if u.User != nil {
		user = u.User.Username()
		password, hasPassword = u.User.Password()
	}
	switch strings.ToLower(u.Scheme) {
	case "ss":
		p.Type = "shadowsocks"
		if u.Path != "" && u.Path != "/" {
			return Profile{}, invalid("uri.path", "unsupported")
		}
		if !hasPassword {
			b, e := decode64(user)
			if e != nil || !utf8.Valid(b) {
				return Profile{}, invalid("shadowsocks.userinfo", "invalid_base64")
			}
			var ok bool
			user, password, ok = strings.Cut(string(b), ":")
			if !ok {
				return Profile{}, invalid("shadowsocks.userinfo", "missing_password")
			}
		}
		p.Shadowsocks = &Shadowsocks{Method: user, Password: password}
		if plugin := q.Get("plugin"); plugin != "" {
			p.Shadowsocks.Plugin, p.Shadowsocks.PluginOptions, _ = strings.Cut(plugin, ";")
			p.Shadowsocks.Plugin = NormalizePlugin(p.Shadowsocks.Plugin)
		}
		if e := knownQuery(q, "plugin"); e != nil {
			return Profile{}, e
		}
	case "socks", "socks5", "socks4", "socks4a":
		p.Type = "socks"
		version := "5"
		if u.Scheme == "socks4" {
			version = "4"
		}
		if u.Scheme == "socks4a" {
			version = "4a"
		}
		p.Socks = &Socks{Version: version, Username: user, Password: password}
		if e := emptyPathQuery(u, q); e != nil {
			return Profile{}, e
		}
	case "http", "https":
		p.Type = "http"
		p.HTTP = &HTTP{Username: user, Password: password}
		if u.Scheme == "https" {
			if p.Port == 0 {
				p.Port = 443
			}
			p.TLS = &TLS{Enabled: true}
		} else if p.Port == 0 {
			p.Port = 80
		}
		if u.Path != "" && u.Path != "/" {
			return Profile{}, invalid("uri.path", "not_proxy_endpoint")
		}
		if e := readTLS(&p, q, u.Scheme == "https"); e != nil {
			return Profile{}, e
		}
		if e := knownQuery(q, "sni", "allowInsecure", "insecure", "alpn", "fp", "security"); e != nil {
			return Profile{}, e
		}
	case "trojan", "anytls":
		if hasPassword {
			return Profile{}, invalid("userinfo", "password_must_be_single_component")
		}
		p.Type = strings.ToLower(u.Scheme)
		if p.Port == 0 {
			p.Port = 443
		}
		if p.Type == "trojan" {
			p.Trojan = &Trojan{Password: user}
		} else {
			p.AnyTLS = &AnyTLS{Password: user}
		}
		if e := readTLS(&p, q, true); e != nil {
			return Profile{}, e
		}
		if p.Type == "trojan" {
			if e := readTransport(&p, q); e != nil {
				return Profile{}, e
			}
			if e := knownQuery(q, standardQuery...); e != nil {
				return Profile{}, e
			}
		} else {
			if e := knownQuery(q, "sni", "allowInsecure", "insecure", "alpn", "fp", "security"); e != nil {
				return Profile{}, e
			}
		}
		if u.Path != "" && u.Path != "/" {
			return Profile{}, invalid("uri.path", "use_path_parameter")
		}
	case "vless", "vmess":
		if hasPassword {
			return Profile{}, invalid("userinfo", "uuid_must_be_single_component")
		}
		p.Type = strings.ToLower(u.Scheme)
		if p.Port == 0 {
			p.Port = 443
		}
		if p.Type == "vless" {
			p.VLESS = &VLESS{UUID: strings.ToLower(user), Flow: q.Get("flow"), PacketEncoding: q.Get("packetEncoding")}
			if enc := q.Get("encryption"); enc != "" && enc != "none" {
				return Profile{}, invalid("vless.encryption", "unsupported")
			}
		} else {
			sec := q.Get("encryption")
			if sec == "" {
				sec = "auto"
			}
			p.VMess = &VMess{UUID: strings.ToLower(user), Security: sec, PacketEncoding: q.Get("packetEncoding")}
		}
		if e := readTLS(&p, q, false); e != nil {
			return Profile{}, e
		}
		if e := readTransport(&p, q); e != nil {
			return Profile{}, e
		}
		if e := knownQuery(q, standardQuery...); e != nil {
			return Profile{}, e
		}
		if u.Path != "" && u.Path != "/" {
			return Profile{}, invalid("uri.path", "use_path_parameter")
		}
	case "hysteria2", "hy2":
		p.Type = "hysteria2"
		if p.Port == 0 {
			p.Port = 443
		}
		if hasPassword {
			user += ":" + password
		}
		p.Hysteria2 = &Hysteria2{Password: user}
		if obfs := q.Get("obfs"); obfs != "" {
			p.Hysteria2.Obfs = &Obfs{Type: obfs, Password: q.Get("obfs-password")}
		} else if q.Has("obfs-password") {
			return Profile{}, invalid("hysteria2.obfs", "required")
		}
		if ranges := q.Get("mport"); ranges != "" {
			p.Hysteria2.ServerPorts = strings.Split(ranges, ",")
		}
		if e := readTLS(&p, q, true); e != nil {
			return Profile{}, e
		}
		if e := knownQuery(q, "sni", "insecure", "allowInsecure", "alpn", "fp", "security", "obfs", "obfs-password", "mport"); e != nil {
			return Profile{}, e
		}
		if u.Path != "" && u.Path != "/" {
			return Profile{}, invalid("uri.path", "unsupported")
		}
	case "tuic":
		p.Type = "tuic"
		if p.Port == 0 {
			p.Port = 443
		}
		p.TUIC = &TUIC{UUID: strings.ToLower(user), Password: password, CongestionControl: q.Get("congestion_control"), UDPRelayMode: q.Get("udp_relay_mode")}
		if p.TUIC.CongestionControl == "" {
			p.TUIC.CongestionControl = "cubic"
		}
		if p.TUIC.UDPRelayMode == "" {
			p.TUIC.UDPRelayMode = "native"
		}
		if q.Has("allow_insecure") {
			if q.Has("insecure") || q.Has("allowInsecure") {
				return Profile{}, invalid("tls.insecure", "conflicting_aliases")
			}
			q.Set("insecure", q.Get("allow_insecure"))
		}
		if e := readTLS(&p, q, true); e != nil {
			return Profile{}, e
		}
		if q.Has("zero_rtt_handshake") {
			b, e := boolean(q.Get("zero_rtt_handshake"))
			if e != nil {
				return Profile{}, e
			}
			p.TUIC.ZeroRTTHandshake = b
		}
		if e := knownQuery(q, "sni", "insecure", "allowInsecure", "allow_insecure", "alpn", "fp", "security", "congestion_control", "udp_relay_mode", "zero_rtt_handshake"); e != nil {
			return Profile{}, e
		}
		if u.Path != "" && u.Path != "/" {
			return Profile{}, invalid("uri.path", "unsupported")
		}
	default:
		return Profile{}, invalid("uri.scheme", "unsupported_protocol")
	}
	if p.Type != "vless" && q.Has("flow") {
		return Profile{}, invalid("query.flow", "requires_vless")
	}
	if p.Type != "vless" && p.Type != "vmess" && (q.Has("encryption") || q.Has("packetEncoding")) {
		return Profile{}, invalid("query", "unsupported_parameter")
	}
	if u.Scheme == "https" && (p.TLS == nil || !p.TLS.Enabled) {
		return Profile{}, invalid("tls", "required_for_https")
	}
	return checked(p)
}
func checked(p Profile) (Profile, error) {
	if e := Validate(p); e != nil {
		return Profile{}, e
	}
	return p, nil
}
func emptyPathQuery(u *url.URL, q url.Values) error {
	if (u.Path != "" && u.Path != "/") || len(q) > 0 {
		return invalid("uri", "unexpected_path_or_query")
	}
	return nil
}
func knownQuery(q url.Values, keys ...string) error {
	allowed := make(map[string]bool, len(keys))
	for _, key := range keys {
		allowed[key] = true
	}
	for key := range q {
		if !allowed[key] {
			return invalid("query", "unsupported_parameter")
		}
	}
	return nil
}

var standardQuery = []string{"security", "sni", "allowInsecure", "insecure", "alpn", "fp", "pbk", "sid", "type", "host", "path", "serviceName", "ed", "eh", "flow", "encryption", "packetEncoding"}

func boolean(s string) (bool, error) {
	switch s {
	case "1", "true":
		return true, nil
	case "0", "false":
		return false, nil
	}
	return false, invalid("query", "invalid_boolean")
}
func readTLS(p *Profile, q url.Values, enabled bool) error {
	if q.Has("security") {
		switch q.Get("security") {
		case "none", "":
			enabled = false
		case "tls", "reality":
			enabled = true
		default:
			return invalid("tls.security", "unsupported")
		}
	}
	t := &TLS{Enabled: enabled, ServerName: q.Get("sni"), Fingerprint: q.Get("fp")}
	if q.Has("alpn") {
		t.ALPN = strings.Split(q.Get("alpn"), ",")
	}
	if q.Has("allowInsecure") && q.Has("insecure") {
		return invalid("tls.insecure", "conflicting_aliases")
	}
	for _, key := range []string{"allowInsecure", "insecure"} {
		if q.Has(key) {
			v, e := boolean(q.Get(key))
			if e != nil {
				return e
			}
			t.Insecure = v
		}
	}
	if q.Get("security") == "reality" {
		t.Reality = &Reality{PublicKey: q.Get("pbk"), ShortID: q.Get("sid")}
	} else if q.Has("pbk") || q.Has("sid") {
		return invalid("tls.reality", "security_required")
	}
	if t.ServerName != "" {
		s, e := NormalizeServer(t.ServerName)
		if e != nil {
			return invalid("tls.server_name", "invalid_host")
		}
		t.ServerName = s
	}
	if !enabled && t.ServerName == "" && !t.Insecure && len(t.ALPN) == 0 && t.Fingerprint == "" && t.Reality == nil {
		p.TLS = nil
	} else {
		p.TLS = t
	}
	return nil
}
func readTransport(p *Profile, q url.Values) error {
	kind := q.Get("type")
	if kind == "" {
		kind = "tcp"
	}
	if kind == "h2" {
		kind = "http"
	}
	tr := &Transport{Type: kind, Path: q.Get("path"), ServiceName: q.Get("serviceName"), EarlyDataHeaderName: q.Get("eh")}
	if q.Has("host") {
		tr.Host = strings.Split(q.Get("host"), ",")
	}
	if q.Has("ed") {
		v, e := strconv.ParseUint(q.Get("ed"), 10, 32)
		if e != nil {
			return invalid("transport.max_early_data", "invalid_integer")
		}
		tr.MaxEarlyData = uint32(v)
	}
	if kind == "tcp" && len(tr.Host) == 0 && tr.Path == "" && tr.ServiceName == "" && tr.MaxEarlyData == 0 && tr.EarlyDataHeaderName == "" {
		p.Transport = nil
	} else {
		p.Transport = tr
	}
	return nil
}
