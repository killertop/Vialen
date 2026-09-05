use crate::model::node::CanonicalNode;
use std::fmt;

/// Deterministic 64-bit fingerprint of canonical content.
/// Computed via a fixed-seed FNV-1a hash over the canonical content representation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Fingerprint(pub u64);

impl Fingerprint {
    pub const FNV_OFFSET_BASIS: u64 = 0xcbf29ce484222325;
    pub const FNV_PRIME: u64 = 0x100000001b3;

    pub fn from_bytes(bytes: &[u8]) -> Self {
        let mut hash = Self::FNV_OFFSET_BASIS;
        for &byte in bytes {
            hash ^= byte as u64;
            hash = hash.wrapping_mul(Self::FNV_PRIME);
        }
        Fingerprint(hash)
    }

    pub fn from_content_key(key: &CanonicalContentKey) -> Self {
        Self::from_bytes(key.as_str().as_bytes())
    }

    pub fn hex(&self) -> String {
        format!("{:016x}", self.0)
    }
}

impl fmt::Display for Fingerprint {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:016x}", self.0)
    }
}

/// Identity key used to determine if two entries represent the "same logical node".
/// In Kotlin RawUpdater.kt:134-147, update matching is based STRICTLY on `displayName()`.
/// It does NOT include protocol.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct CanonicalIdentityKey {
    pub identifier: String,
}

impl CanonicalIdentityKey {
    pub fn new(identifier: impl Into<String>) -> Self {
        Self {
            identifier: identifier.into(),
        }
    }

    /// Matches Kotlin RawUpdater matching by displayName (name if present, else displayAddress).
    /// Strictly keys on displayName() without protocol.
    pub fn from_display_name(node: &CanonicalNode) -> Self {
        Self {
            identifier: node.display_name(),
        }
    }

    /// Structured bean-family endpoint key, with a known Kotlin compatibility gap:
    /// Kotlin concatenates address + port + class without delimiters, so distinct
    /// endpoints such as node1:23 and node12:3 compare equal there but not here.
    /// This key must not be described as exact production dedup parity.
    /// (bean_family + serverAddress + serverPort).
    /// - Shadowsocks: separate ("shadowsocks")
    /// - SOCKS (socks4, socks4a, socks5): same SOCKS family ("socks")
    /// - Trojan: separate ("trojan")
    /// - TUIC: separate ("tuic")
    /// - Hysteria (hysteria1, hysteria2): same Hysteria family ("hysteria")
    ///
    /// Uses collision-safe length-prefixed structured encoding: {len}:{family}{len}:{server}{len}:{port}
    pub fn from_endpoint(node: &CanonicalNode) -> Self {
        let family = node.bean_family();
        let port_str = node.port.to_string();
        let identifier = format!(
            "{}:{}{}:{}{}:{}",
            family.encode_utf16().count(),
            family,
            node.server.encode_utf16().count(),
            node.server,
            port_str.encode_utf16().count(),
            port_str
        );
        Self { identifier }
    }

    pub fn to_key_string(&self) -> String {
        self.identifier.clone()
    }
}

impl fmt::Display for CanonicalIdentityKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.identifier)
    }
}

/// Content key covering ALL canonical semantic fields of a node.
/// Used to determine whether a node's content has changed.
/// Serialized with length-prefixing to guarantee uniqueness and prevent delimiter clashes.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct CanonicalContentKey(pub String);

impl CanonicalContentKey {
    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn fingerprint(&self) -> Fingerprint {
        Fingerprint::from_content_key(self)
    }

    /// Build a canonical content key from a CanonicalNode.
    /// Covers all protocol, server, port, credentials, plugin, name, TLS, transport, and extra fields.
    pub fn from_node(node: &CanonicalNode) -> Self {
        let mut s = String::with_capacity(512);

        fn write_field(buf: &mut String, val: &str) {
            use std::fmt::Write;
            let _ = write!(buf, "{}:{}", val.encode_utf16().count(), val);
        }

        write_field(&mut s, &node.protocol);
        write_field(&mut s, &node.server);
        write_field(&mut s, &node.port.to_string());
        write_field(&mut s, &node.username);
        write_field(&mut s, &node.password);
        write_field(&mut s, &node.plugin);
        write_field(&mut s, &node.name);

        write_field(&mut s, if node.tls.is_some() { "1" } else { "0" });
        if let Some(ref tls) = node.tls {
            write_field(&mut s, if tls.enabled { "1" } else { "0" });
            write_field(&mut s, &tls.server_name);
            let mut alpn_buf = String::new();
            for a in &tls.alpn {
                write_field(&mut alpn_buf, a);
            }
            write_field(&mut s, &alpn_buf);
            write_field(&mut s, if tls.allow_insecure { "1" } else { "0" });
            write_field(&mut s, if tls.disable_sni { "1" } else { "0" });
            write_field(&mut s, &tls.reality_public_key);
            write_field(&mut s, &tls.reality_short_id);
            write_field(&mut s, &tls.certificates);
            write_field(&mut s, &tls.utls_fingerprint);
        } else {
            for _ in 0..9 {
                write_field(&mut s, "");
            }
        }

        write_field(&mut s, if node.transport.is_some() { "1" } else { "0" });
        if let Some(ref tr) = node.transport {
            write_field(&mut s, &tr.transport_type);
            write_field(&mut s, &tr.host);
            write_field(&mut s, &tr.path);
            write_field(&mut s, &tr.early_data_header_name);
            write_field(&mut s, &tr.max_early_data.to_string());
            write_field(&mut s, &tr.service_name);
            write_field(&mut s, &tr.packet_encoding.to_string());
        } else {
            for _ in 0..7 {
                write_field(&mut s, "");
            }
        }

        write_field(&mut s, if node.extra.is_some() { "1" } else { "0" });
        if let Some(ref ex) = node.extra {
            write_field(&mut s, &ex.congestion_control);
            write_field(&mut s, &ex.udp_relay_mode);
            write_field(&mut s, &ex.obfs_type);
            write_field(&mut s, &ex.obfs_password);
            write_field(&mut s, &ex.upload_mbps.to_string());
            write_field(&mut s, &ex.download_mbps.to_string());
            write_field(&mut s, &ex.mport);
            write_field(&mut s, &ex.auth_payload);
            write_field(&mut s, if ex.disable_chrome_parrot { "1" } else { "0" });
            write_field(&mut s, &ex.bbr_profile);
            write_field(&mut s, &ex.hop_interval_max.to_string());
            write_field(&mut s, &ex.obfs_min_packet_size.to_string());
            write_field(&mut s, &ex.obfs_max_packet_size.to_string());
            write_field(&mut s, &ex.alter_id.to_string());
            write_field(&mut s, &ex.encryption);
        } else {
            for _ in 0..15 {
                write_field(&mut s, "");
            }
        }

        CanonicalContentKey(s)
    }
}

impl fmt::Display for CanonicalContentKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_every_semantic_field_changes_content_key() {
        use crate::model::node::{ExtraConfig, TlsConfig, TransportConfig};
        let mut baseline =
            CanonicalNode::new("trojan", "example.com", 443, "", "secret", "", "Node");
        baseline.tls = Some(TlsConfig::default());
        baseline.transport = Some(TransportConfig::default());
        baseline.extra = Some(ExtraConfig::default());
        let key = baseline.content_key();
        macro_rules! changed {
            ($label:expr, $node:ident, $mutation:expr) => {{
                let mut $node = baseline.clone();
                $mutation;
                assert_ne!($node, baseline, "fixture must change {}", $label);
                assert_ne!($node.content_key(), key, "key omitted {}", $label);
            }};
        }
        changed!("protocol", n, n.protocol.push_str("changed"));
        changed!("server", n, n.server.push_str("changed"));
        changed!("username", n, n.username.push_str("changed"));
        changed!("password", n, n.password.push_str("changed"));
        changed!("plugin", n, n.plugin.push_str("changed"));
        changed!("name", n, n.name.push_str("changed"));
        changed!(
            "tls.server_name",
            n,
            n.tls.as_mut().unwrap().server_name.push_str("changed")
        );
        changed!(
            "tls.reality_public_key",
            n,
            n.tls
                .as_mut()
                .unwrap()
                .reality_public_key
                .push_str("changed")
        );
        changed!(
            "tls.reality_short_id",
            n,
            n.tls.as_mut().unwrap().reality_short_id.push_str("changed")
        );
        changed!(
            "tls.certificates",
            n,
            n.tls.as_mut().unwrap().certificates.push_str("changed")
        );
        changed!(
            "tls.utls_fingerprint",
            n,
            n.tls.as_mut().unwrap().utls_fingerprint.push_str("changed")
        );
        changed!(
            "transport.transport_type",
            n,
            n.transport
                .as_mut()
                .unwrap()
                .transport_type
                .push_str("changed")
        );
        changed!(
            "transport.host",
            n,
            n.transport.as_mut().unwrap().host.push_str("changed")
        );
        changed!(
            "transport.path",
            n,
            n.transport.as_mut().unwrap().path.push_str("changed")
        );
        changed!(
            "transport.early_data_header_name",
            n,
            n.transport
                .as_mut()
                .unwrap()
                .early_data_header_name
                .push_str("changed")
        );
        changed!(
            "transport.service_name",
            n,
            n.transport
                .as_mut()
                .unwrap()
                .service_name
                .push_str("changed")
        );
        changed!(
            "extra.congestion_control",
            n,
            n.extra
                .as_mut()
                .unwrap()
                .congestion_control
                .push_str("changed")
        );
        changed!(
            "extra.udp_relay_mode",
            n,
            n.extra.as_mut().unwrap().udp_relay_mode.push_str("changed")
        );
        changed!(
            "extra.obfs_type",
            n,
            n.extra.as_mut().unwrap().obfs_type.push_str("changed")
        );
        changed!(
            "extra.obfs_password",
            n,
            n.extra.as_mut().unwrap().obfs_password.push_str("changed")
        );
        changed!(
            "extra.mport",
            n,
            n.extra.as_mut().unwrap().mport.push_str("changed")
        );
        changed!(
            "extra.auth_payload",
            n,
            n.extra.as_mut().unwrap().auth_payload.push_str("changed")
        );
        changed!(
            "extra.bbr_profile",
            n,
            n.extra.as_mut().unwrap().bbr_profile.push_str("changed")
        );
        changed!(
            "extra.encryption",
            n,
            n.extra.as_mut().unwrap().encryption.push_str("changed")
        );
        changed!("port", n, n.port += 1);
        changed!("tls.enabled", n, n.tls.as_mut().unwrap().enabled = true);
        changed!(
            "tls.allow_insecure",
            n,
            n.tls.as_mut().unwrap().allow_insecure = true
        );
        changed!(
            "tls.disable_sni",
            n,
            n.tls.as_mut().unwrap().disable_sni = true
        );
        changed!(
            "extra.disable_chrome_parrot",
            n,
            n.extra.as_mut().unwrap().disable_chrome_parrot = true
        );
        changed!(
            "transport.max_early_data",
            n,
            n.transport.as_mut().unwrap().max_early_data += 1
        );
        changed!(
            "transport.packet_encoding",
            n,
            n.transport.as_mut().unwrap().packet_encoding += 1
        );
        changed!(
            "extra.upload_mbps",
            n,
            n.extra.as_mut().unwrap().upload_mbps += 1
        );
        changed!(
            "extra.download_mbps",
            n,
            n.extra.as_mut().unwrap().download_mbps += 1
        );
        changed!(
            "extra.hop_interval_max",
            n,
            n.extra.as_mut().unwrap().hop_interval_max += 1
        );
        changed!(
            "extra.obfs_min_packet_size",
            n,
            n.extra.as_mut().unwrap().obfs_min_packet_size += 1
        );
        changed!(
            "extra.obfs_max_packet_size",
            n,
            n.extra.as_mut().unwrap().obfs_max_packet_size += 1
        );
        changed!("extra.alter_id", n, n.extra.as_mut().unwrap().alter_id += 1);
        changed!(
            "tls.alpn",
            n,
            n.tls.as_mut().unwrap().alpn.push("h2".into())
        );
        changed!("tls.presence", n, n.tls = None);
        changed!("transport.presence", n, n.transport = None);
        changed!("extra.presence", n, n.extra = None);
    }

    #[test]
    fn test_fingerprint_fixed_vectors() {
        assert_eq!(Fingerprint::from_bytes(b"").hex(), "cbf29ce484222325");
        assert_eq!(Fingerprint::from_bytes(b"a").hex(), "af63dc4c8601ec8c");
        assert_eq!(Fingerprint::from_bytes(b"foobar").hex(), "85944171f73967e8");
    }

    #[test]
    fn test_content_key_and_fingerprint_across_processes() {
        use crate::model::node::{ExtraConfig, TlsConfig, TransportConfig};
        let mut node = CanonicalNode::new(
            "trojan",
            "2001:db8::1",
            443,
            "user",
            "secret",
            "",
            "Node 🚀|:\n",
        );
        node.tls = Some(TlsConfig {
            enabled: true,
            alpn: vec!["h2".into(), "h3".into()],
            server_name: "example.com".into(),
            ..Default::default()
        });
        node.transport = Some(TransportConfig {
            path: "/path:|🚀".into(),
            ..Default::default()
        });
        node.extra = Some(ExtraConfig::default());
        let snapshot = format!(
            "CANONICAL_SNAPSHOT={:?}|{}",
            node.content_key(),
            node.fingerprint()
        );
        const CHILD_FLAG: &str = "VIALEN_CANONICAL_STABILITY_CHILD";
        if std::env::var_os(CHILD_FLAG).is_some() {
            println!("{snapshot}");
            return;
        }
        for _ in 0..2 {
            let output = std::process::Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "model::key::tests::test_content_key_and_fingerprint_across_processes",
                    "--nocapture",
                ])
                .env(CHILD_FLAG, "1")
                .output()
                .unwrap();
            assert!(output.status.success(), "child failed: {:?}", output);
            let stdout = String::from_utf8(output.stdout).unwrap();
            assert!(
                stdout.lines().any(|line| line == snapshot),
                "missing or different child snapshot: {stdout}"
            );
        }
    }

    #[test]
    fn test_fingerprint_determinism() {
        let node1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "secret", "", "Node1");
        let node2 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "secret", "", "Node1");
        let key1 = node1.content_key();
        let key2 = node2.content_key();
        assert_eq!(key1, key2);
        assert_eq!(key1.fingerprint(), key2.fingerprint());
    }

    #[test]
    fn test_content_key_detects_changes() {
        let mut node1 =
            CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "secret", "", "Node1");
        let key1 = node1.content_key();

        // Change password
        node1.password = "newsecret".to_string();
        let key2 = node1.content_key();
        assert_ne!(key1, key2);

        // Change port
        node1.port = 8443;
        let key3 = node1.content_key();
        assert_ne!(key2, key3);

        // Change name
        node1.name = "Renamed".to_string();
        let key4 = node1.content_key();
        assert_ne!(key3, key4);
    }

    #[test]
    fn test_identity_key_semantics() {
        let node1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "secret", "", "Node1");
        let mut node2 = CanonicalNode::new(
            "ss",
            "1.1.1.2",
            8389,
            "aes-128-gcm",
            "newsecret",
            "",
            "Node1",
        );

        // Same display name -> same identity
        let id1 = CanonicalIdentityKey::from_display_name(&node1);
        let id2 = CanonicalIdentityKey::from_display_name(&node2);
        assert_eq!(id1, id2);

        // Same display name across DIFFERENT protocols -> same identity in RawUpdater!
        let node_trojan = CanonicalNode::new("trojan", "1.1.1.3", 443, "", "pass", "", "Node1");
        let id_trojan = CanonicalIdentityKey::from_display_name(&node_trojan);
        assert_eq!(id1, id_trojan);

        // Different endpoints
        let ep1 = CanonicalIdentityKey::from_endpoint(&node1);
        let ep2 = CanonicalIdentityKey::from_endpoint(&node2);
        assert_ne!(ep1, ep2);

        // Empty name fallback to server:port
        node2.name = "".to_string();
        let id_empty = CanonicalIdentityKey::from_display_name(&node2);
        assert_eq!(id_empty.identifier, "1.1.1.2:8389");

        // IPv6 unbracketed fallback wraps in brackets
        let node_ipv6 = CanonicalNode::new("ss", "2001:db8::1", 8388, "", "pass", "", "");
        let id_ipv6 = CanonicalIdentityKey::from_display_name(&node_ipv6);
        assert_eq!(id_ipv6.identifier, "[2001:db8::1]:8388");

        // Bracketed IPv6 fallback stays bracketed
        let node_ipv6_bracket = CanonicalNode::new("ss", "[2001:db8::1]", 8388, "", "pass", "", "");
        let id_ipv6_bracket = CanonicalIdentityKey::from_display_name(&node_ipv6_bracket);
        assert_eq!(id_ipv6_bracket.identifier, "[2001:db8::1]:8388");

        // Unicode names
        let node_unicode = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "pass", "", "节点 ⚡ 01");
        let id_unicode = CanonicalIdentityKey::from_display_name(&node_unicode);
        assert_eq!(id_unicode.identifier, "节点 ⚡ 01");

        // Bean family endpoint dedup:
        // SOCKS family: socks4 and socks5 at same endpoint share the identical endpoint key!
        let node_s4 = CanonicalNode::new("socks4", "1.1.1.1", 1080, "", "", "", "S4");
        let node_s5 = CanonicalNode::new("socks5", "1.1.1.1", 1080, "", "", "", "S5");
        assert_eq!(
            CanonicalIdentityKey::from_endpoint(&node_s4),
            CanonicalIdentityKey::from_endpoint(&node_s5)
        );

        // SS separate: SS and SOCKS at same endpoint do NOT share endpoint key!
        let node_ss = CanonicalNode::new("ss", "1.1.1.1", 1080, "", "", "", "SS");
        assert_ne!(
            CanonicalIdentityKey::from_endpoint(&node_ss),
            CanonicalIdentityKey::from_endpoint(&node_s5)
        );

        // Hysteria family: hysteria1 and hysteria2 at same endpoint share identical endpoint key!
        let node_hy1 = CanonicalNode::new("hysteria", "1.1.1.1", 443, "", "", "", "Hy1");
        let node_hy2 = CanonicalNode::new("hysteria2", "1.1.1.1", 443, "", "", "", "Hy2");
        assert_eq!(
            CanonicalIdentityKey::from_endpoint(&node_hy1),
            CanonicalIdentityKey::from_endpoint(&node_hy2)
        );

        // Trojan and TUIC separate
        let node_tr = CanonicalNode::new("trojan", "1.1.1.1", 443, "", "", "", "Tr");
        let node_tuic = CanonicalNode::new("tuic", "1.1.1.1", 443, "", "", "", "Tuic");
        assert_ne!(
            CanonicalIdentityKey::from_endpoint(&node_tr),
            CanonicalIdentityKey::from_endpoint(&node_tuic)
        );
    }

    #[test]
    fn test_content_key_injectivity_and_collision_resistance() {
        use crate::model::node::{ExtraConfig, TlsConfig, TransportConfig};

        // 1. ALPN comma collision resistance
        let mut node_alpn1 = CanonicalNode::new("trojan", "1.1.1.1", 443, "", "pass", "", "N1");
        node_alpn1.tls = Some(TlsConfig {
            alpn: vec!["h2,h3".to_string()],
            ..Default::default()
        });

        let mut node_alpn2 = CanonicalNode::new("trojan", "1.1.1.1", 443, "", "pass", "", "N1");
        node_alpn2.tls = Some(TlsConfig {
            alpn: vec!["h2".to_string(), "h3".to_string()],
            ..Default::default()
        });

        assert_ne!(
            node_alpn1.content_key(),
            node_alpn2.content_key(),
            "ALPN with comma must NOT collide with multiple ALPN entries"
        );
        assert_ne!(node_alpn1.fingerprint(), node_alpn2.fingerprint());

        // 2. TLS None vs Some(default) presence marker
        let node_no_tls = CanonicalNode::new("trojan", "1.1.1.1", 443, "", "pass", "", "N1");
        let mut node_empty_tls = node_no_tls.clone();
        node_empty_tls.tls = Some(TlsConfig::default());
        assert_ne!(
            node_no_tls.content_key(),
            node_empty_tls.content_key(),
            "None TLS vs Some(default) TLS must have distinct content keys"
        );

        // 3. Transport None vs Some(default) presence marker
        let node_no_tr = CanonicalNode::new("trojan", "1.1.1.1", 443, "", "pass", "", "N1");
        let mut node_empty_tr = node_no_tr.clone();
        node_empty_tr.transport = Some(TransportConfig::default());
        assert_ne!(
            node_no_tr.content_key(),
            node_empty_tr.content_key(),
            "None Transport vs Some(default) Transport must have distinct content keys"
        );

        // 4. Extra None vs Some(default) presence marker
        let node_no_ex = CanonicalNode::new("trojan", "1.1.1.1", 443, "", "pass", "", "N1");
        let mut node_empty_ex = node_no_ex.clone();
        node_empty_ex.extra = Some(ExtraConfig::default());
        assert_ne!(
            node_no_ex.content_key(),
            node_empty_ex.content_key(),
            "None Extra vs Some(default) Extra must have distinct content keys"
        );

        // 5. tls.enabled distinguishes enabled vs disabled
        let mut node_tls_off = node_empty_tls.clone();
        node_tls_off.tls.as_mut().unwrap().enabled = false;
        let mut node_tls_on = node_empty_tls.clone();
        node_tls_on.tls.as_mut().unwrap().enabled = true;
        assert_ne!(node_tls_off.content_key(), node_tls_on.content_key());

        // 6. transport.service_name change is detected
        let mut node_tr_svc1 = node_empty_tr.clone();
        node_tr_svc1.transport.as_mut().unwrap().service_name = "GunService1".to_string();
        let mut node_tr_svc2 = node_empty_tr.clone();
        node_tr_svc2.transport.as_mut().unwrap().service_name = "GunService2".to_string();
        assert_ne!(node_tr_svc1.content_key(), node_tr_svc2.content_key());
    }
}
