use super::node::CanonicalNode;
use std::net::{Ipv4Addr, Ipv6Addr};
use std::str::FromStr;

pub struct NormalizationEngine;

impl NormalizationEngine {
    pub fn normalize_host(raw_host: &str) -> String {
        let trimmed = raw_host.trim();
        let unbracketed = if trimmed.starts_with('[') && trimmed.ends_with(']') {
            &trimmed[1..trimmed.len() - 1]
        } else {
            trimmed
        };

        if let Ok(ipv6) = Ipv6Addr::from_str(unbracketed) {
            ipv6.to_string()
        } else if let Ok(ipv4) = Ipv4Addr::from_str(unbracketed) {
            ipv4.to_string()
        } else {
            unbracketed.to_ascii_lowercase()
        }
    }

    pub fn normalize_plugin(raw_plugin: &str) -> String {
        let trimmed = raw_plugin.trim();
        if trimmed == "simple-obfs" || trimmed.starts_with("simple-obfs;") {
            trimmed.replacen("simple-obfs", "obfs-local", 1)
        } else {
            trimmed.to_string()
        }
    }

    pub fn normalize_name(raw_name: &str) -> String {
        raw_name.trim().to_string()
    }

    pub fn normalize_node(node: &mut CanonicalNode) {
        node.server = Self::normalize_host(&node.server);
        node.plugin = Self::normalize_plugin(&node.plugin);
        node.name = Self::normalize_name(&node.name);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_normalize_host() {
        assert_eq!(
            NormalizationEngine::normalize_host("EXAMPLE.COM"),
            "example.com"
        );
        assert_eq!(
            NormalizationEngine::normalize_host("  127.0.0.1  "),
            "127.0.0.1"
        );
        assert_eq!(
            NormalizationEngine::normalize_host("[2001:0db8::0001]"),
            "2001:db8::1"
        );
        assert_eq!(
            NormalizationEngine::normalize_host("2001:0DB8:0000:0000:0000:0000:0000:0001"),
            "2001:db8::1"
        );
        assert_eq!(NormalizationEngine::normalize_host("[::1]"), "::1");
    }

    #[test]
    fn test_normalize_plugin() {
        assert_eq!(
            NormalizationEngine::normalize_plugin("simple-obfs"),
            "obfs-local"
        );
        assert_eq!(
            NormalizationEngine::normalize_plugin(" simple-obfs-custom;mode=tls "),
            "simple-obfs-custom;mode=tls"
        );
        assert_eq!(
            NormalizationEngine::normalize_plugin("simple-obfs;obfs=http"),
            "obfs-local;obfs=http"
        );
        assert_eq!(
            NormalizationEngine::normalize_plugin("obfs-local;obfs=tls"),
            "obfs-local;obfs=tls"
        );
    }

    #[test]
    fn test_normalized_content_equivalence_and_idempotence() {
        for (left, right) in [
            (" EXAMPLE.COM ", "example.com"),
            ("[2001:0DB8:0:0:0:0:0:1]", "2001:db8::1"),
        ] {
            let mut a = CanonicalNode::new(
                "ss",
                left,
                443,
                "",
                "secret",
                " simple-obfs;obfs=tls ",
                " Node ",
            );
            let mut b = CanonicalNode::new(
                "ss",
                right,
                443,
                "",
                "secret",
                "obfs-local;obfs=tls",
                "Node",
            );
            assert_ne!(a.content_key(), b.content_key());
            NormalizationEngine::normalize_node(&mut a);
            NormalizationEngine::normalize_node(&mut b);
            assert_eq!(a.content_key(), b.content_key());
            assert_eq!(a.fingerprint(), b.fingerprint());
            let once = a.clone();
            NormalizationEngine::normalize_node(&mut a);
            assert_eq!(a, once);
        }
    }
}
