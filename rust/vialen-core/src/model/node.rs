use super::normalize::NormalizationEngine;
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Protocol {
    Shadowsocks,
    Socks4,
    Socks4a,
    Socks5,
    Trojan,
    Tuic,
    Hysteria1,
    Hysteria2,
    Vless,
    Vmess,
    Unknown,
}

impl Protocol {
    pub fn as_str(&self) -> &'static str {
        match self {
            Protocol::Shadowsocks => "shadowsocks",
            Protocol::Socks4 => "socks4",
            Protocol::Socks4a => "socks4a",
            Protocol::Socks5 => "socks5",
            Protocol::Trojan => "trojan",
            Protocol::Tuic => "tuic",
            Protocol::Hysteria1 => "hysteria1",
            Protocol::Hysteria2 => "hysteria2",
            Protocol::Vless => "vless",
            Protocol::Vmess => "vmess",
            Protocol::Unknown => "unknown",
        }
    }

    pub fn from_str_loose(s: &str) -> Self {
        match s.to_ascii_lowercase().as_str() {
            "ss" | "shadowsocks" => Protocol::Shadowsocks,
            "socks4" => Protocol::Socks4,
            "socks4a" => Protocol::Socks4a,
            "socks" | "socks5" => Protocol::Socks5,
            "trojan" => Protocol::Trojan,
            "tuic" => Protocol::Tuic,
            "hysteria" | "hysteria1" | "hy1" => Protocol::Hysteria1,
            "hysteria2" | "hy2" => Protocol::Hysteria2,
            "vless" => Protocol::Vless,
            "vmess" => Protocol::Vmess,
            _ => Protocol::Unknown,
        }
    }
}

impl fmt::Display for Protocol {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TransportConfig {
    pub transport_type: String, // tcp, udp, ws, grpc, http, httpupgrade
    pub host: String,
    pub path: String,
    pub early_data_header_name: String,
    pub max_early_data: i32,
    pub service_name: String,
    pub packet_encoding: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TlsConfig {
    pub enabled: bool,
    pub server_name: String,
    pub alpn: Vec<String>,
    pub allow_insecure: bool,
    pub disable_sni: bool,
    pub reality_public_key: String,
    pub reality_short_id: String,
    pub certificates: String,
    pub utls_fingerprint: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExtraConfig {
    pub congestion_control: String,
    pub udp_relay_mode: String,
    pub obfs_type: String,
    pub obfs_password: String,
    pub upload_mbps: i32,
    pub download_mbps: i32,
    pub mport: String,
    pub auth_payload: String,
    pub disable_chrome_parrot: bool,
    pub bbr_profile: String,
    pub hop_interval_max: i32,
    pub obfs_min_packet_size: i32,
    pub obfs_max_packet_size: i32,
    pub alter_id: i32,
    pub encryption: String,
}

impl Default for ExtraConfig {
    fn default() -> Self {
        Self {
            congestion_control: String::new(),
            udp_relay_mode: String::new(),
            obfs_type: "salamander".to_string(),
            obfs_password: String::new(),
            upload_mbps: 0,
            download_mbps: 0,
            mport: String::new(),
            auth_payload: String::new(),
            disable_chrome_parrot: false,
            bbr_profile: String::new(),
            hop_interval_max: 0,
            obfs_min_packet_size: 512,
            obfs_max_packet_size: 1200,
            alter_id: 0,
            encryption: String::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CanonicalNode {
    pub protocol: String,
    pub server: String,
    pub port: u16,
    pub username: String,
    pub password: String,
    pub plugin: String,
    pub name: String,
    pub transport: Option<TransportConfig>,
    pub tls: Option<TlsConfig>,
    pub extra: Option<ExtraConfig>,
}

impl CanonicalNode {
    pub fn new(
        protocol: &str,
        server: &str,
        port: u16,
        username: &str,
        password: &str,
        plugin: &str,
        name: &str,
    ) -> Self {
        Self {
            protocol: protocol.to_string(),
            server: server.to_string(),
            port,
            username: username.to_string(),
            password: password.to_string(),
            plugin: plugin.to_string(),
            name: name.to_string(),
            transport: None,
            tls: None,
            extra: None,
        }
    }

    pub fn protocol_kind(&self) -> Protocol {
        Protocol::from_str_loose(&self.protocol)
    }

    pub fn to_normalized(&self) -> Self {
        let mut cloned = self.clone();
        cloned.normalize();
        cloned
    }

    pub fn normalize(&mut self) {
        NormalizationEngine::normalize_node(self);
    }

    pub fn display_name(&self) -> String {
        if is_not_blank(&self.name) {
            self.name.clone()
        } else {
            format!("{}:{}", wrap_ipv6_host(&self.server), self.port)
        }
    }

    pub fn identity_key(&self) -> super::key::CanonicalIdentityKey {
        super::key::CanonicalIdentityKey::from_display_name(self)
    }

    pub fn content_key(&self) -> super::key::CanonicalContentKey {
        super::key::CanonicalContentKey::from_node(self)
    }

    pub fn fingerprint(&self) -> super::key::Fingerprint {
        self.content_key().fingerprint()
    }

    /// Maps a node's protocol to its production bean-family:
    /// - Shadowsocks: "shadowsocks"
    /// - SOCKS (socks, socks4, socks4a, socks5): "socks"
    /// - Trojan: "trojan"
    /// - TUIC: "tuic"
    /// - Hysteria (hysteria, hysteria1, hysteria2, hy, hy2): "hysteria"
    /// - V2Ray / VMess / VLESS (both instantiate VMessBean): "vmess"
    pub fn bean_family(&self) -> &str {
        match self.protocol.to_ascii_lowercase().as_str() {
            "shadowsocks" | "ss" => "shadowsocks",
            "socks" | "socks4" | "socks4a" | "socks5" => "socks",
            "trojan" => "trojan",
            "tuic" => "tuic",
            "hysteria" | "hysteria1" | "hysteria2" | "hy" | "hy2" => "hysteria",
            "wireguard" | "wg" => "wireguard",
            "http" | "https" => "http",
            "vmess" | "vless" => "vmess",
            _ => &self.protocol,
        }
    }
}

pub fn unwrap_ipv6_host(s: &str) -> &str {
    let mut cur = s;
    while cur.starts_with('[') && cur.ends_with(']') && cur.len() >= 2 {
        cur = &cur[1..cur.len() - 1];
    }
    cur
}

pub fn wrap_ipv6_host(s: &str) -> String {
    let unwrapped = unwrap_ipv6_host(s);
    if unwrapped.parse::<std::net::Ipv6Addr>().is_ok() {
        format!("[{}]", unwrapped)
    } else {
        s.to_string()
    }
}

pub fn is_not_blank(s: &str) -> bool {
    s.chars().any(|c| !c.is_whitespace())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_not_blank_semantics() {
        assert!(!is_not_blank(""));
        assert!(!is_not_blank("   "));
        assert!(!is_not_blank("\t\n\r "));
        assert!(!is_not_blank("\u{00A0}")); // NBSP
        assert!(!is_not_blank("\u{3000}")); // Ideographic space
        assert!(!is_not_blank(" \u{00A0} \u{3000} \t "));

        assert!(is_not_blank("a"));
        assert!(is_not_blank("  a  "));
        assert!(is_not_blank("东京 01"));
        assert!(is_not_blank("Tokyo 🔥 Server"));
    }

    #[test]
    fn test_display_name_whitespace_and_ipv6_parity() {
        // Non-blank name preserves whitespace
        let node1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "", "", "  My Node  ");
        assert_eq!(node1.display_name(), "  My Node  ");

        // Blank names fallback to endpoint
        let blank_cases = ["", "  ", "\t\n", "\u{00A0}", "\u{3000}"];
        for b in blank_cases {
            let n = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "", "", b);
            assert_eq!(n.display_name(), "1.1.1.1:8388");
        }

        // IPv4
        let n_v4 = CanonicalNode::new("ss", "192.168.1.1", 1080, "", "", "", "");
        assert_eq!(n_v4.display_name(), "192.168.1.1:1080");

        // Valid unbracketed IPv6
        let n_v6 = CanonicalNode::new("ss", "2001:db8::1", 8388, "", "", "", "");
        assert_eq!(n_v6.display_name(), "[2001:db8::1]:8388");

        // Valid bracketed IPv6
        let n_v6_b = CanonicalNode::new("ss", "[2001:db8::1]", 8388, "", "", "", "");
        assert_eq!(n_v6_b.display_name(), "[2001:db8::1]:8388");

        // Valid loopback IPv6
        let n_v6_loop = CanonicalNode::new("ss", "::1", 8388, "", "", "", "");
        assert_eq!(n_v6_loop.display_name(), "[::1]:8388");

        // Invalid host containing colon (abc:def) must NOT be bracketed
        let n_invalid = CanonicalNode::new("ss", "abc:def", 8388, "", "", "", "");
        assert_eq!(n_invalid.display_name(), "abc:def:8388");

        // Domain name
        let n_dom = CanonicalNode::new("ss", "example.com", 443, "", "", "", "");
        assert_eq!(n_dom.display_name(), "example.com:443");
    }
}
