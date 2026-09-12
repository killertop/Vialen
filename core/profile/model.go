// Package profile defines validated, platform-independent proxy profiles.
package profile

// Profile uses one protocol-specific options object. Validate rejects conflicting
// objects rather than allowing irrelevant fields to affect configuration output.
type Profile struct {
	UDPOverTCP  *UDPOverTCP  `json:"udp_over_tcp,omitempty"`
	Multiplex   *Multiplex   `json:"multiplex,omitempty"`
	ID          string       `json:"id,omitempty"`
	Name        string       `json:"name,omitempty"`
	Type        string       `json:"type"`
	Server      string       `json:"server"`
	Port        uint16       `json:"port"`
	TLS         *TLS         `json:"tls,omitempty"`
	Transport   *Transport   `json:"transport,omitempty"`
	Socks       *Socks       `json:"socks,omitempty"`
	HTTP        *HTTP        `json:"http,omitempty"`
	Shadowsocks *Shadowsocks `json:"shadowsocks,omitempty"`
	VMess       *VMess       `json:"vmess,omitempty"`
	VLESS       *VLESS       `json:"vless,omitempty"`
	Trojan      *Trojan      `json:"trojan,omitempty"`
	Hysteria2   *Hysteria2   `json:"hysteria2,omitempty"`
	TUIC        *TUIC        `json:"tuic,omitempty"`
	WireGuard   *WireGuard   `json:"wireguard,omitempty"`
	ShadowTLS   *ShadowTLS   `json:"shadowtls,omitempty"`
	AnyTLS      *AnyTLS      `json:"anytls,omitempty"`
}

type ShadowTLS struct {
	Version  uint32 `json:"version"`
	Password string `json:"password,omitempty"`
}

type AnyTLS struct {
	Password string `json:"password"`
}

type UDPOverTCP struct {
	Enabled bool   `json:"enabled"`
	Version uint32 `json:"version,omitempty"`
}
type Multiplex struct {
	Enabled        bool   `json:"enabled"`
	Protocol       string `json:"protocol,omitempty"`
	MaxConnections uint32 `json:"max_connections,omitempty"`
	MinStreams     uint32 `json:"min_streams,omitempty"`
	MaxStreams     uint32 `json:"max_streams,omitempty"`
	Padding        bool   `json:"padding,omitempty"`
}
type ECH struct {
	Enabled         bool     `json:"enabled"`
	Config          []string `json:"config,omitempty"`
	QueryServerName string   `json:"query_server_name,omitempty"`
}
type TLS struct {
	DisableSNI  bool     `json:"disable_sni,omitempty"`
	ECH         *ECH     `json:"ech,omitempty"`
	Enabled     bool     `json:"enabled"`
	ServerName  string   `json:"server_name,omitempty"`
	Insecure    bool     `json:"insecure,omitempty"`
	ALPN        []string `json:"alpn,omitempty"`
	Fingerprint string   `json:"fingerprint,omitempty"`
	Certificate string   `json:"certificate,omitempty"`
	Reality     *Reality `json:"reality,omitempty"`
}
type Reality struct {
	PublicKey string `json:"public_key"`
	ShortID   string `json:"short_id,omitempty"`
}
type Transport struct {
	Headers             map[string][]string `json:"headers,omitempty"`
	Type                string              `json:"type"`
	Host                []string            `json:"host,omitempty"`
	Path                string              `json:"path,omitempty"`
	ServiceName         string              `json:"service_name,omitempty"`
	MaxEarlyData        uint32              `json:"max_early_data,omitempty"`
	EarlyDataHeaderName string              `json:"early_data_header_name,omitempty"`
}
type Socks struct {
	Version  string `json:"version"`
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
}
type HTTP struct {
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
}
type Shadowsocks struct {
	Method        string `json:"method"`
	Password      string `json:"password"`
	Plugin        string `json:"plugin,omitempty"`
	PluginOptions string `json:"plugin_options,omitempty"`
}
type VMess struct {
	PacketEncoding string `json:"packet_encoding,omitempty"`
	UUID           string `json:"uuid"`
	Security       string `json:"security,omitempty"`
	AlterID        uint32 `json:"alter_id,omitempty"`
}
type VLESS struct {
	PacketEncoding string `json:"packet_encoding,omitempty"`
	UUID           string `json:"uuid"`
	Flow           string `json:"flow,omitempty"`
}
type Trojan struct {
	Password string `json:"password"`
}
type Hysteria2 struct {
	HopInterval         uint32   `json:"hop_interval,omitempty"`
	HopIntervalMax      uint32   `json:"hop_interval_max,omitempty"`
	BBRProfile          string   `json:"bbr_profile,omitempty"`
	DisableChromeParrot bool     `json:"disable_chrome_parrot,omitempty"`
	Password            string   `json:"password"`
	Obfs                *Obfs    `json:"obfs,omitempty"`
	UpMbps              uint32   `json:"up_mbps,omitempty"`
	DownMbps            uint32   `json:"down_mbps,omitempty"`
	ServerPorts         []string `json:"server_ports,omitempty"`
}
type Obfs struct {
	MinPacketSize uint32 `json:"min_packet_size,omitempty"`
	MaxPacketSize uint32 `json:"max_packet_size,omitempty"`
	Type          string `json:"type"`
	Password      string `json:"password"`
}
type TUIC struct {
	UUID              string `json:"uuid"`
	Password          string `json:"password"`
	CongestionControl string `json:"congestion_control,omitempty"`
	UDPRelayMode      string `json:"udp_relay_mode,omitempty"`
	ZeroRTTHandshake  bool   `json:"zero_rtt_handshake,omitempty"`
}
type WireGuard struct {
	PrivateKey          string   `json:"private_key"`
	PublicKey           string   `json:"public_key"`
	PreSharedKey        string   `json:"pre_shared_key,omitempty"`
	Address             []string `json:"address"`
	AllowedIPs          []string `json:"allowed_ips,omitempty"`
	MTU                 uint32   `json:"mtu,omitempty"`
	Reserved            []uint32 `json:"reserved,omitempty"`
	PersistentKeepalive uint32   `json:"persistent_keepalive,omitempty"`
}

// NormalizePlugin maps the widely used SIP003 obfuscation plugin alias to its registered name.
func NormalizePlugin(name string) string {
	if name == "simple-obfs" || name == "obfs" {
		return "obfs-local"
	}
	return name
}
