package profile

import (
	"encoding/base64"
	"encoding/json"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// ExportURI emits an interoperable share link. Local storage identity is never
// exported. Options that the selected share format cannot encode are rejected;
// callers can offer profile JSON or a complete configuration for those profiles.
func ExportURI(p Profile) (string, error) {
	if e := Validate(p); e != nil {
		return "", e
	}
	if p.UDPOverTCP != nil || p.Multiplex != nil {
		return "", invalid("advanced_options", "uri_export_unsupported")
	}
	if p.TLS != nil && (p.TLS.ECH != nil || p.TLS.DisableSNI) {
		return "", invalid("tls", "uri_export_unsupported")
	}
	if h := p.Hysteria2; h != nil && (h.HopInterval != 0 || h.HopIntervalMax != 0 || h.BBRProfile != "" || h.DisableChromeParrot || h.Obfs != nil && (h.Obfs.Type == "gecko" || h.Obfs.MinPacketSize != 0 || h.Obfs.MaxPacketSize != 0)) {
		return "", invalid("hysteria2", "uri_export_unsupported")
	}
	if p.Type == "wireguard" || p.Type == "shadowtls" {
		return "", invalid(p.Type, "uri_export_unsupported")
	}
	if p.TLS != nil && p.TLS.Certificate != "" {
		return "", invalid("tls.certificate", "uri_export_unsupported")
	}
	if p.Transport != nil && len(p.Transport.Headers) > 0 {
		return "", invalid("transport.headers", "uri_export_unsupported")
	}
	if p.TLS != nil {
		if e := commaList(p.TLS.ALPN, "tls.alpn"); e != nil {
			return "", e
		}
	}
	if p.Transport != nil {
		if e := commaList(p.Transport.Host, "transport.host"); e != nil {
			return "", e
		}
	}
	if p.Type == "vmess" {
		return exportVMess(p)
	}
	server, e := NormalizeServer(p.Server)
	if e != nil {
		return "", e
	}
	u := &url.URL{Scheme: p.Type, Host: net.JoinHostPort(server, strconv.Itoa(int(p.Port))), Fragment: p.Name}
	q := url.Values{}
	if e := exportTLS(p, q); e != nil {
		return "", e
	}
	if tr := p.Transport; tr != nil {
		q.Set("type", tr.Type)
		if len(tr.Host) > 0 {
			q.Set("host", strings.Join(tr.Host, ","))
		}
		if tr.Path != "" {
			q.Set("path", tr.Path)
		}
		if tr.ServiceName != "" {
			q.Set("serviceName", tr.ServiceName)
		}
		if tr.MaxEarlyData != 0 {
			q.Set("ed", strconv.FormatUint(uint64(tr.MaxEarlyData), 10))
		}
		if tr.EarlyDataHeaderName != "" {
			q.Set("eh", tr.EarlyDataHeaderName)
		}
	}
	switch p.Type {
	case "shadowsocks":
		s := p.Shadowsocks
		u.Scheme = "ss"
		if strings.Contains(s.Plugin, ";") {
			return "", invalid("shadowsocks.plugin", "uri_export_unsupported")
		}
		// SIP002 requires clear percent-encoded userinfo for AEAD-2022;
		// Base64URL userinfo is recommended for the earlier AEAD ciphers.
		if strings.HasPrefix(s.Method, "2022-") {
			u.User = url.UserPassword(s.Method, s.Password)
		} else {
			u.User = url.User(base64.RawURLEncoding.EncodeToString([]byte(s.Method + ":" + s.Password)))
		}
		if s.Plugin != "" {
			plugin := s.Plugin
			if s.PluginOptions != "" {
				plugin += ";" + s.PluginOptions
			}
			u.Path = "/"
			q.Set("plugin", plugin)
		}
	case "socks":
		switch p.Socks.Version {
		case "4":
			u.Scheme = "socks4"
		case "4a":
			u.Scheme = "socks4a"
		case "5":
			u.Scheme = "socks5"
		}
		u.User = exportCredentials(p.Socks.Username, p.Socks.Password)
	case "http":
		if p.TLS != nil && p.TLS.Enabled {
			u.Scheme = "https"
		}
		u.User = exportCredentials(p.HTTP.Username, p.HTTP.Password)
	case "trojan":
		u.User = url.User(p.Trojan.Password)
	case "anytls":
		u.User = url.User(p.AnyTLS.Password)
	case "vless":
		u.User = url.User(p.VLESS.UUID)
		q.Set("encryption", "none")
		if p.VLESS.Flow != "" {
			q.Set("flow", p.VLESS.Flow)
		}
		if p.VLESS.PacketEncoding != "" {
			q.Set("packetEncoding", p.VLESS.PacketEncoding)
		}
	case "hysteria2":
		h := p.Hysteria2
		if h.UpMbps != 0 {
			return "", invalid("hysteria2.up_mbps", "uri_export_unsupported")
		}
		if h.DownMbps != 0 {
			return "", invalid("hysteria2.down_mbps", "uri_export_unsupported")
		}
		u.User = url.User(h.Password)
		if h.Obfs != nil {
			q.Set("obfs", h.Obfs.Type)
			q.Set("obfs-password", h.Obfs.Password)
		}
		if len(h.ServerPorts) > 0 {
			q.Set("mport", strings.Join(h.ServerPorts, ","))
		}
	case "tuic":
		t := p.TUIC
		u.User = url.UserPassword(t.UUID, t.Password)
		if t.CongestionControl != "" {
			q.Set("congestion_control", t.CongestionControl)
		}
		if t.UDPRelayMode != "" {
			q.Set("udp_relay_mode", t.UDPRelayMode)
		}
		if t.ZeroRTTHandshake {
			q.Set("zero_rtt_handshake", "1")
		}
	}
	u.RawQuery = q.Encode()
	out := u.String()
	if len(out) > MaxURIBytes {
		return "", invalid("uri", "too_large")
	}
	return out, nil
}
func exportCredentials(username, password string) *url.Userinfo {
	if password != "" {
		return url.UserPassword(username, password)
	}
	if username != "" {
		return url.User(username)
	}
	return nil
}
func exportTLS(p Profile, q url.Values) error {
	t := p.TLS
	if t == nil || !t.Enabled {
		return nil
	}
	if p.Type == "vless" || p.Type == "trojan" {
		q.Set("security", "tls")
	}
	if t.Reality != nil {
		if p.Type != "vless" && p.Type != "trojan" {
			return invalid("tls.reality", "uri_export_unsupported")
		}
		q.Set("security", "reality")
		q.Set("pbk", t.Reality.PublicKey)
		if t.Reality.ShortID != "" {
			q.Set("sid", t.Reality.ShortID)
		}
	}
	if t.ServerName != "" {
		q.Set("sni", t.ServerName)
	}
	if t.Insecure {
		q.Set("insecure", "1")
	}
	if len(t.ALPN) > 0 {
		q.Set("alpn", strings.Join(t.ALPN, ","))
	}
	if t.Fingerprint != "" {
		q.Set("fp", t.Fingerprint)
	}
	return nil
}
func commaList(values []string, field string) error {
	for _, s := range values {
		if strings.Contains(s, ",") {
			return invalid(field, "uri_export_unsupported")
		}
	}
	return nil
}
func exportVMess(p Profile) (string, error) {
	if p.VMess.PacketEncoding != "" {
		return "", invalid("vmess.packet_encoding", "uri_export_unsupported")
	}
	if p.TLS != nil {
		if p.TLS.Insecure {
			return "", invalid("tls.insecure", "vmess_json_export_unsupported")
		}
		if p.TLS.Reality != nil {
			return "", invalid("tls.reality", "vmess_json_export_unsupported")
		}
	}
	m := map[string]any{"v": "2", "ps": p.Name, "add": p.Server, "port": p.Port, "id": p.VMess.UUID, "aid": p.VMess.AlterID, "scy": p.VMess.Security, "net": "tcp", "type": "none"}
	if p.TLS != nil && p.TLS.Enabled {
		m["tls"] = "tls"
		if p.TLS.ServerName != "" {
			m["sni"] = p.TLS.ServerName
		}
		if len(p.TLS.ALPN) > 0 {
			m["alpn"] = strings.Join(p.TLS.ALPN, ",")
		}
		if p.TLS.Fingerprint != "" {
			m["fp"] = p.TLS.Fingerprint
		}
	}
	if tr := p.Transport; tr != nil {
		if tr.MaxEarlyData != 0 || tr.EarlyDataHeaderName != "" {
			return "", invalid("transport.early_data", "vmess_json_export_unsupported")
		}
		m["net"] = tr.Type
		if len(tr.Host) > 0 {
			m["host"] = strings.Join(tr.Host, ",")
		}
		if tr.Type == "grpc" {
			m["path"] = tr.ServiceName
		} else if tr.Path != "" {
			m["path"] = tr.Path
		}
	}
	data, e := json.Marshal(m)
	if e != nil {
		return "", invalid("vmess", "json_export_failed")
	}
	out := "vmess://" + base64.StdEncoding.EncodeToString(data)
	if len(out) > MaxURIBytes {
		return "", invalid("uri", "too_large")
	}
	return out, nil
}
