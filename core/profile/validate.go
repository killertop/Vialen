package profile

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/net/idna"
)

// Error identifies a bad field without including credentials or complete URIs.
type Error struct {
	Field string
	Code  string
}

func (e *Error) Error() string         { return e.Field + ": " + e.Code }
func invalid(field, code string) error { return &Error{field, code} }

var hostIDNA = idna.New(idna.MapForLookup(), idna.Transitional(false), idna.StrictDomainName(true), idna.BidiRule(), idna.VerifyDNSLength(true))

func NormalizeServer(s string) (string, error) {
	if s == "" || strings.TrimSpace(s) != s || !utf8.ValidString(s) {
		return "", invalid("server", "invalid_host")
	}
	if strings.HasPrefix(s, "[") && strings.HasSuffix(s, "]") {
		s = s[1 : len(s)-1]
	}
	if ip, e := netip.ParseAddr(s); e == nil {
		if ip.Zone() != "" {
			return "", invalid("server", "zone_not_supported")
		}
		return ip.String(), nil
	}
	if strings.ContainsAny(s, "/:@?#%\\") || strings.ContainsFunc(s, unicode.IsSpace) {
		return "", invalid("server", "invalid_host")
	}
	ascii, e := hostIDNA.ToASCII(s)
	if e != nil || ascii == "" {
		return "", invalid("server", "invalid_host")
	}
	labels := strings.Split(strings.TrimSuffix(ascii, "."), ".")
	for _, label := range labels {
		if label == "" || len(label) > 63 {
			return "", invalid("server", "invalid_host")
		}
	}
	return strings.ToLower(ascii), nil
}
func validUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for _, i := range []int{8, 13, 18, 23} {
		if s[i] != '-' {
			return false
		}
	}
	_, e := hex.DecodeString(strings.ReplaceAll(s, "-", ""))
	return e == nil
}
func secret(s string) bool { return strings.TrimSpace(s) != "" && utf8.ValidString(s) }
func (p Profile) DisplayName() string {
	if p.Name != "" {
		return p.Name
	}
	return net.JoinHostPort(p.Server, strconv.Itoa(int(p.Port)))
}

func Validate(p Profile) error {
	if !utf8.ValidString(p.ID) || !utf8.ValidString(p.Name) {
		return invalid("profile", "invalid_utf8")
	}
	if _, e := NormalizeServer(p.Server); e != nil {
		return e
	}
	if p.Port == 0 {
		return invalid("port", "out_of_range")
	}
	selected := map[string]bool{"socks": p.Socks != nil, "http": p.HTTP != nil, "shadowsocks": p.Shadowsocks != nil, "vmess": p.VMess != nil, "vless": p.VLESS != nil, "trojan": p.Trojan != nil, "hysteria2": p.Hysteria2 != nil, "tuic": p.TUIC != nil, "wireguard": p.WireGuard != nil, "anytls": p.AnyTLS != nil, "shadowtls": p.ShadowTLS != nil}
	present, known := selected[p.Type]
	if !known {
		return invalid("type", "unsupported_protocol")
	}
	if !present {
		return invalid(p.Type, "missing_options")
	}
	for kind, exists := range selected {
		if kind != p.Type && exists {
			return invalid("profile", "conflicting_protocol_options")
		}
	}
	switch p.Type {
	case "socks":
		if p.Socks.Version != "4" && p.Socks.Version != "4a" && p.Socks.Version != "5" {
			return invalid("socks.version", "unsupported_version")
		}
		if p.Socks.Version != "5" && p.Socks.Password != "" {
			return invalid("socks.password", "requires_socks5")
		}
	case "http":
	case "shadowtls":
		if p.ShadowTLS.Version < 1 || p.ShadowTLS.Version > 3 {
			return invalid("shadowtls.version", "out_of_range")
		}
		if p.ShadowTLS.Version > 1 && !secret(p.ShadowTLS.Password) {
			return invalid("shadowtls.password", "required")
		}
		if p.TLS != nil && p.TLS.Reality != nil {
			return invalid("tls.reality", "unsupported_for_protocol")
		}
	case "anytls":
		if !secret(p.AnyTLS.Password) {
			return invalid("anytls.password", "required")
		}
	case "shadowsocks":
		if !secret(p.Shadowsocks.Password) {
			return invalid("shadowsocks.password", "required")
		}
		switch p.Shadowsocks.Method {
		case "aes-128-gcm", "aes-192-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305":
		default:
			return invalid("shadowsocks.method", "unsupported_method")
		}
		if strings.HasPrefix(p.Shadowsocks.Method, "2022-") {
			size := 32
			if p.Shadowsocks.Method == "2022-blake3-aes-128-gcm" {
				size = 16
			}
			for _, key := range strings.Split(p.Shadowsocks.Password, ":") {
				b, e := decode64(key)
				if e != nil || len(b) != size {
					return invalid("shadowsocks.password", "invalid_psk")
				}
			}
		}
		if p.Shadowsocks.Plugin == "" && p.Shadowsocks.PluginOptions != "" {
			return invalid("shadowsocks.plugin", "required")
		}
	case "vmess":
		if !packetEncoding(p.VMess.PacketEncoding) {
			return invalid("vmess.packet_encoding", "unsupported_encoding")
		}
		if !validUUID(p.VMess.UUID) {
			return invalid("vmess.uuid", "invalid_uuid")
		}
		switch p.VMess.Security {
		case "", "auto", "none", "zero", "aes-128-gcm", "chacha20-poly1305":
		default:
			return invalid("vmess.security", "unsupported_security")
		}
	case "vless":
		if !packetEncoding(p.VLESS.PacketEncoding) {
			return invalid("vless.packet_encoding", "unsupported_encoding")
		}
		if !validUUID(p.VLESS.UUID) {
			return invalid("vless.uuid", "invalid_uuid")
		}
		if p.VLESS.Flow != "" && p.VLESS.Flow != "xtls-rprx-vision" {
			return invalid("vless.flow", "unsupported_flow")
		}
		if p.VLESS.Flow != "" && p.Transport != nil && p.Transport.Type != "tcp" {
			return invalid("vless.flow", "requires_tcp")
		}
	case "trojan":
		if !secret(p.Trojan.Password) {
			return invalid("trojan.password", "required")
		}
	case "hysteria2":
		if !secret(p.Hysteria2.Password) {
			return invalid("hysteria2.password", "required")
		}
		if obfs := p.Hysteria2.Obfs; obfs != nil {
			if (obfs.Type != "salamander" && obfs.Type != "gecko") || !secret(obfs.Password) {
				return invalid("hysteria2.obfs", "invalid_obfuscation")
			}
		}
		for _, r := range p.Hysteria2.ServerPorts {
			if !validPortRange(r) {
				return invalid("hysteria2.server_ports", "invalid_range")
			}
		}
	case "tuic":
		if !validUUID(p.TUIC.UUID) {
			return invalid("tuic.uuid", "invalid_uuid")
		}
		if !secret(p.TUIC.Password) {
			return invalid("tuic.password", "required")
		}
		switch p.TUIC.CongestionControl {
		case "", "cubic", "new_reno", "bbr":
		default:
			return invalid("tuic.congestion_control", "unsupported_algorithm")
		}
		switch p.TUIC.UDPRelayMode {
		case "", "native", "quic":
		default:
			return invalid("tuic.udp_relay_mode", "unsupported_mode")
		}
	case "wireguard":
		if e := validateWG(*p.WireGuard); e != nil {
			return e
		}
	}
	if p.Type == "shadowtls" || p.Type == "anytls" || p.Type == "trojan" || p.Type == "hysteria2" || p.Type == "tuic" {
		if p.TLS == nil || !p.TLS.Enabled {
			return invalid("tls", "required")
		}
	}
	if p.TLS != nil {
		switch p.Type {
		case "shadowtls", "anytls", "http", "vmess", "vless", "trojan", "hysteria2", "tuic":
		default:
			return invalid("tls", "unsupported_for_protocol")
		}
		if e := validateTLS(*p.TLS); e != nil {
			return e
		}
	}
	if p.Transport != nil {
		switch p.Type {
		case "vmess", "vless", "trojan":
		default:
			return invalid("transport", "unsupported_for_protocol")
		}
		if e := validateTransport(*p.Transport); e != nil {
			return e
		}
	}
	if e := validateAdvanced(p); e != nil {
		return e
	}
	return nil
}
func validateTLS(t TLS) error {
	if !t.Enabled && (t.ServerName != "" || t.Insecure || len(t.ALPN) > 0 || t.Fingerprint != "" || t.Certificate != "" || t.Reality != nil || t.ECH != nil || t.DisableSNI) {
		return invalid("tls", "options_require_enabled")
	}
	if t.ServerName != "" {
		if _, e := NormalizeServer(t.ServerName); e != nil {
			return invalid("tls.server_name", "invalid_host")
		}
	}
	for _, a := range t.ALPN {
		if len(a) == 0 || len(a) > 255 || strings.ContainsRune(a, 0) {
			return invalid("tls.alpn", "invalid_protocol")
		}
	}
	if t.Reality != nil {
		if t.Insecure {
			return invalid("tls.reality", "insecure_conflict")
		}
		b, e := decode64(t.Reality.PublicKey)
		if e != nil || len(b) != 32 {
			return invalid("tls.reality.public_key", "invalid_key")
		}
		s := t.Reality.ShortID
		if len(s) > 16 || len(s)%2 != 0 {
			return invalid("tls.reality.short_id", "invalid_hex")
		}
		if _, e := hex.DecodeString(s); e != nil {
			return invalid("tls.reality.short_id", "invalid_hex")
		}
	}
	return nil
}
func validateTransport(t Transport) error {
	switch t.Type {
	case "tcp":
		if len(t.Headers) > 0 || len(t.Host) > 0 || t.Path != "" || t.ServiceName != "" || t.MaxEarlyData != 0 || t.EarlyDataHeaderName != "" {
			return invalid("transport", "tcp_has_no_options")
		}
	case "ws", "http", "httpupgrade":
		if t.ServiceName != "" {
			return invalid("transport.service_name", "requires_grpc")
		}
		if t.Type != "ws" && (t.MaxEarlyData != 0 || t.EarlyDataHeaderName != "") {
			return invalid("transport.early_data", "requires_ws")
		}
		if t.Path != "" && !strings.HasPrefix(t.Path, "/") {
			return invalid("transport.path", "must_start_with_slash")
		}
	case "grpc":
		if len(t.Headers) > 0 || len(t.Host) > 0 || t.Path != "" || t.MaxEarlyData != 0 || t.EarlyDataHeaderName != "" {
			return invalid("transport", "invalid_grpc_options")
		}
	default:
		return invalid("transport.type", "unsupported_transport")
	}
	for name, values := range t.Headers {
		if !validHeaderName(name) {
			return invalid("transport.headers", "invalid_name")
		}
		if strings.EqualFold(name, "Host") && len(t.Host) > 0 {
			return invalid("transport.headers", "duplicate_host")
		}
		for _, v := range values {
			if strings.ContainsAny(v, "\r\n\x00") {
				return invalid("transport.headers", "invalid_value")
			}
		}
	}
	for _, h := range t.Host {
		if !secret(h) || strings.ContainsAny(h, "\r\n\x00") {
			return invalid("transport.host", "invalid_header")
		}
	}
	if strings.ContainsAny(t.Path, "\r\n\x00") || strings.ContainsAny(t.ServiceName, "\r\n\x00") || (t.EarlyDataHeaderName != "" && !validHeaderName(t.EarlyDataHeaderName)) {
		return invalid("transport", "invalid_text")
	}
	return nil
}
func validateWG(w WireGuard) error {
	for _, k := range []struct{ name, value string }{{"private_key", w.PrivateKey}, {"public_key", w.PublicKey}, {"pre_shared_key", w.PreSharedKey}} {
		if k.name == "pre_shared_key" && k.value == "" {
			continue
		}
		b, e := base64.StdEncoding.Strict().DecodeString(k.value)
		if e != nil || len(b) != 32 {
			return invalid("wireguard."+k.name, "invalid_key")
		}
	}
	if len(w.Address) == 0 {
		return invalid("wireguard.address", "required")
	}
	for _, a := range append(append([]string{}, w.Address...), w.AllowedIPs...) {
		if _, e := netip.ParsePrefix(a); e != nil {
			return invalid("wireguard.address", "invalid_cidr")
		}
	}
	for _, v := range w.Reserved {
		if v > 255 {
			return invalid("wireguard.reserved", "out_of_range")
		}
	}
	if len(w.Reserved) != 0 && len(w.Reserved) != 3 {
		return invalid("wireguard.reserved", "requires_three_bytes")
	}
	if w.MTU != 0 && (w.MTU < 576 || w.MTU > 65535) {
		return invalid("wireguard.mtu", "out_of_range")
	}
	return nil
}
func validPortRange(s string) bool {
	a, b, rangeOK := strings.Cut(s, ":")
	lo, e := port(a)
	if e != nil {
		return false
	}
	if !rangeOK {
		return true
	}
	hi, e := port(b)
	return e == nil && hi >= lo
}
func port(s string) (uint16, error) {
	if s == "" {
		return 0, invalid("port", "required")
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, invalid("port", "invalid_integer")
		}
	}
	n, e := strconv.ParseUint(s, 10, 16)
	if e != nil || n == 0 {
		return 0, invalid("port", "out_of_range")
	}
	return uint16(n), nil
}
func decode64(s string) ([]byte, error) {
	if strings.ContainsAny(s, " \t\r\n") {
		return nil, invalid("base64", "unexpected_whitespace")
	}
	for _, enc := range []*base64.Encoding{base64.StdEncoding.Strict(), base64.RawStdEncoding.Strict(), base64.URLEncoding.Strict(), base64.RawURLEncoding.Strict()} {
		if b, e := enc.DecodeString(s); e == nil {
			return b, nil
		}
	}
	return nil, invalid("base64", "invalid_encoding")
}

// DecodeBase64 accepts standard and URL-safe padded/unpadded data. Whitespace is
// deliberately the importer's responsibility, not a decoder recovery heuristic.
func DecodeBase64(s string) ([]byte, error) { return decode64(s) }
func (p Profile) String() string            { return fmt.Sprintf("Profile(type=%s)", p.Type) }

func packetEncoding(s string) bool { return s == "" || s == "xudp" || s == "packetaddr" }

func validHeaderName(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || strings.ContainsRune("!#$%&'*+-.^_`|~", c) {
			continue
		}
		return false
	}
	return true
}
