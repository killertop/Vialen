package profile

import (
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Common VMess QR JSON permits numeric fields as JSON numbers or decimal strings.
// All other fields remain strings; no Android JSON coercion or relaxed syntax.
func parseVMessJSON(encoded string) (Profile, error) {
	data, e := decode64(encoded)
	if e != nil || !utf8.Valid(data) {
		return Profile{}, invalid("vmess", "invalid_base64_json")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	token, e := d.Token()
	if e != nil || token != json.Delim('{') {
		return Profile{}, invalid("vmess", "expected_json_object")
	}
	fields := map[string]json.RawMessage{}
	for d.More() {
		key, e := d.Token()
		if e != nil {
			return Profile{}, invalid("vmess", "invalid_json")
		}
		name, ok := key.(string)
		if !ok {
			return Profile{}, invalid("vmess", "invalid_json")
		}
		if _, exists := fields[name]; exists {
			return Profile{}, invalid("vmess", "duplicate_json_field")
		}
		var raw json.RawMessage
		if d.Decode(&raw) != nil {
			return Profile{}, invalid("vmess", "invalid_json")
		}
		fields[name] = raw
	}
	if _, e = d.Token(); e != nil {
		return Profile{}, invalid("vmess", "invalid_json")
	}
	if _, e = d.Token(); e != io.EOF {
		return Profile{}, invalid("vmess", "trailing_json")
	}
	m := map[string]string{}
	for key, raw := range fields {
		switch key {
		case "v", "port", "aid":
			var s string
			if json.Unmarshal(raw, &s) == nil {
				m[key] = s
			} else {
				var n json.Number
				dec := json.NewDecoder(bytes.NewReader(raw))
				dec.UseNumber()
				if dec.Decode(&n) != nil || string(raw) == "null" {
					return Profile{}, invalid("vmess."+key, "invalid_integer")
				}
				m[key] = n.String()
			}
		case "ps", "add", "id", "scy", "net", "type", "host", "path", "tls", "sni", "alpn", "fp":
			var s string
			if string(raw) == "null" || json.Unmarshal(raw, &s) != nil {
				return Profile{}, invalid("vmess."+key, "expected_string")
			}
			m[key] = s
		default:
			return Profile{}, invalid("vmess", "unsupported_json_field")
		}
	}
	server, e := NormalizeServer(m["add"])
	if e != nil {
		return Profile{}, e
	}
	endpointPort, e := port(m["port"])
	if e != nil {
		return Profile{}, e
	}
	p := Profile{Name: m["ps"], Type: "vmess", Server: server, Port: endpointPort, VMess: &VMess{UUID: strings.ToLower(m["id"]), Security: m["scy"]}}
	if p.VMess.Security == "" {
		p.VMess.Security = "auto"
	}
	if aid, ok := m["aid"]; ok {
		v, e := strconv.ParseUint(aid, 10, 32)
		if e != nil {
			return Profile{}, invalid("vmess.alter_id", "invalid_integer")
		}
		p.VMess.AlterID = uint32(v)
	}
	switch m["tls"] {
	case "", "none":
		if m["sni"] != "" || m["alpn"] != "" || m["fp"] != "" {
			return Profile{}, invalid("tls", "options_require_enabled")
		}
	case "tls":
		p.TLS = &TLS{Enabled: true, ServerName: m["sni"], Fingerprint: m["fp"]}
		if m["alpn"] != "" {
			p.TLS.ALPN = strings.Split(m["alpn"], ",")
		}
		if p.TLS.ServerName != "" {
			p.TLS.ServerName, e = NormalizeServer(p.TLS.ServerName)
			if e != nil {
				return Profile{}, invalid("tls.server_name", "invalid_host")
			}
		}
	default:
		return Profile{}, invalid("vmess.tls", "unsupported_security")
	}
	kind := m["net"]
	if kind == "" {
		kind = "tcp"
	}
	if kind == "h2" {
		kind = "http"
	}
	header := m["type"]
	if kind == "tcp" && header == "http" {
		kind = "http"
	} else if header != "" && header != "none" {
		return Profile{}, invalid("vmess.type", "unsupported_header")
	}
	tr := &Transport{Type: kind, Path: m["path"]}
	if m["host"] != "" {
		tr.Host = strings.Split(m["host"], ",")
	}
	if kind == "grpc" {
		tr.ServiceName = tr.Path
		tr.Path = ""
	}
	if kind != "tcp" || len(tr.Host) > 0 || tr.Path != "" {
		p.Transport = tr
	}
	return p, nil
}
