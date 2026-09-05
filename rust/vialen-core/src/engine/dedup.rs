use crate::model::key::{CanonicalContentKey, CanonicalIdentityKey, Fingerprint};
use crate::model::node::CanonicalNode;
use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DedupResult {
    /// Ordered list of unique canonical nodes.
    pub unique_nodes: Vec<CanonicalNode>,
    /// Indices of nodes that were identified as duplicates and discarded.
    pub duplicate_indices: Vec<usize>,
    /// Total count of duplicates eliminated.
    pub deduplicated_count: usize,
}

pub struct DedupEngine;

impl DedupEngine {
    /// Exact opaque keys projected from initialized production Beans by Kotlin.
    /// Returns stable unique-group ranks, including a rank for each duplicate.
    pub fn rank_keys<E>(
        keys: impl IntoIterator<Item = Result<Vec<u16>, E>>,
    ) -> Result<Vec<i32>, E> {
        let mut ranks = HashMap::new();
        let mut result = Vec::new();
        for key in keys {
            let key = key?;
            // JNI arrays cannot contain more than i32::MAX elements.
            let next = ranks.len() as i32;
            let rank = *ranks.entry(key).or_insert(next);
            result.push(rank);
        }
        Ok(result)
    }

    /// Deduplicates nodes by exact content.
    /// Uses Fingerprint for accelerated bucketing, but MUST verify equality via CanonicalContentKey
    /// to strictly prevent hash collisions from causing false deduplication.
    pub fn dedup_by_content(nodes: &[CanonicalNode]) -> DedupResult {
        Self::dedup_by_content_with_fingerprint(nodes, CanonicalContentKey::fingerprint)
    }

    // Keep equality and bucketing in one implementation so tests can force collisions.
    fn dedup_by_content_with_fingerprint(
        nodes: &[CanonicalNode],
        fingerprint: impl Fn(&CanonicalContentKey) -> Fingerprint,
    ) -> DedupResult {
        let mut unique_nodes = Vec::with_capacity(nodes.len());
        let mut duplicate_indices = Vec::new();

        // Map Fingerprint -> List of (CanonicalContentKey, original_index)
        let mut seen: HashMap<Fingerprint, Vec<CanonicalContentKey>> = HashMap::new();

        for (idx, node) in nodes.iter().enumerate() {
            let key = node.content_key();
            let fp = fingerprint(&key);

            let mut is_duplicate = false;
            if let Some(existing_keys) = seen.get(&fp) {
                // Confirm actual key equality (collision safety requirement)
                for existing_key in existing_keys {
                    if *existing_key == key {
                        is_duplicate = true;
                        break;
                    }
                }
            }

            if is_duplicate {
                duplicate_indices.push(idx);
            } else {
                seen.entry(fp).or_default().push(key);
                unique_nodes.push(node.clone());
            }
        }

        let deduplicated_count = duplicate_indices.len();
        DedupResult {
            unique_nodes,
            duplicate_indices,
            deduplicated_count,
        }
    }

    /// AUDIT: ConfigBean Dedup Gap
    /// In Kotlin `Protocols.Deduplication.hash()`:
    /// `if (bean is ConfigBean) return bean.config; else return bean.serverAddress + bean.serverPort + type;`
    /// `ConfigBean` represents a raw JSON configuration (sing-box / xray raw config profile) without
    /// standard server address / port structure.
    /// CanonicalNode is strictly designed for standard structured proxy protocols (Shadowsocks, SOCKS,
    /// Trojan, TUIC, Hysteria, etc.) and intentionally does not represent or parse unstructured JSON profiles.
    /// Therefore, ConfigBean dedup remains a documented and audited gap handled at the Kotlin layer.
    pub fn dedup_by_endpoint(nodes: &[CanonicalNode]) -> DedupResult {
        let mut unique_nodes = Vec::with_capacity(nodes.len());
        let mut duplicate_indices = Vec::new();
        let mut seen = HashSet::new();

        for (idx, node) in nodes.iter().enumerate() {
            let ep = CanonicalIdentityKey::from_endpoint(node);
            if seen.contains(&ep) {
                duplicate_indices.push(idx);
            } else {
                seen.insert(ep);
                unique_nodes.push(node.clone());
            }
        }

        let deduplicated_count = duplicate_indices.len();
        DedupResult {
            unique_nodes,
            duplicate_indices,
            deduplicated_count,
        }
    }

    /// Disambiguates duplicate display names in-place,
    /// strictly matching Kotlin `RawUpdater.kt` lines 89-102.
    /// If multiple nodes have identical display name, suffixes " (1)", " (2)", etc.
    pub fn disambiguate_names(nodes: &mut [CanonicalNode]) {
        let mut seen = HashSet::new();

        for node in nodes.iter_mut() {
            let mut index = 0;
            let mut name = node.display_name();

            while seen.contains(&name) {
                index += 1;
                let prev_suffix = format!(" ({})", index - 1);
                name = name.replace(&prev_suffix, "");
                name = format!("{} ({})", name, index);
                node.name = name.clone();
            }
            seen.insert(node.display_name());
        }
    }

    /// Test bridge helper: takes raw length-prefixed URIs, applies disambiguate_names, and returns serialized canonical nodes.
    pub fn disambiguate_names_raw(payload: &str) -> String {
        use crate::engine::batch::{
            MAX_BATCH_ITEMS, MAX_PAYLOAD_BYTES, decode_length_prefixed_items_strict,
            encode_length_prefixed_items,
        };
        use crate::parser::{parse_proxy, serialize_canonical_node};

        if payload.len() > MAX_PAYLOAD_BYTES {
            return "ERROR|INVALID_INPUT|Payload exceeds maximum 10MB limit".to_string();
        }
        let uris = match decode_length_prefixed_items_strict(payload) {
            Ok(u) => u,
            Err(err) => return format!("ERROR|INTERNAL_ERROR|{}", err),
        };
        if uris.len() > MAX_BATCH_ITEMS {
            return "ERROR|INVALID_INPUT|Item count exceeds limit".to_string();
        }

        let mut nodes = Vec::with_capacity(uris.len());
        for (idx, u) in uris.iter().enumerate() {
            match parse_proxy(u) {
                Ok(node) => nodes.push(node),
                Err(e) => return format!("ERROR|PARSE_FAILED|{}|{:?}", idx, e),
            }
        }

        Self::disambiguate_names(&mut nodes);
        let serialized: Vec<String> = nodes.iter().map(serialize_canonical_node).collect();
        let refs: Vec<&str> = serialized.iter().map(|s| s.as_str()).collect();
        encode_length_prefixed_items(&refs)
    }

    /// Test bridge helper: takes raw length-prefixed URIs, applies dedup_by_endpoint, and returns serialized canonical nodes.
    pub fn dedup_by_endpoint_raw(payload: &str) -> String {
        use crate::engine::batch::{
            MAX_BATCH_ITEMS, MAX_PAYLOAD_BYTES, decode_length_prefixed_items_strict,
            encode_length_prefixed_items,
        };
        use crate::parser::{parse_proxy, serialize_canonical_node};

        if payload.len() > MAX_PAYLOAD_BYTES {
            return "ERROR|INVALID_INPUT|Payload exceeds maximum 10MB limit".to_string();
        }
        let uris = match decode_length_prefixed_items_strict(payload) {
            Ok(u) => u,
            Err(err) => return format!("ERROR|INTERNAL_ERROR|{}", err),
        };
        if uris.len() > MAX_BATCH_ITEMS {
            return "ERROR|INVALID_INPUT|Item count exceeds limit".to_string();
        }

        let mut nodes = Vec::with_capacity(uris.len());
        for (idx, u) in uris.iter().enumerate() {
            match parse_proxy(u) {
                Ok(node) => nodes.push(node),
                Err(e) => return format!("ERROR|PARSE_FAILED|{}|{:?}", idx, e),
            }
        }

        let dedup = Self::dedup_by_endpoint(&nodes);
        let serialized: Vec<String> = dedup
            .unique_nodes
            .iter()
            .map(serialize_canonical_node)
            .collect();
        let refs: Vec<&str> = serialized.iter().map(|s| s.as_str()).collect();
        encode_length_prefixed_items(&refs)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn production_key_ranks_are_exact_and_ordered() {
        use super::DedupEngine;
        let keys: Vec<Result<Vec<u16>, ()>> =
            vec![Ok(vec![]), Ok(vec![0xd800]), Ok(vec![]), Ok(vec![0xd801])];
        assert_eq!(DedupEngine::rank_keys(keys), Ok(vec![0, 1, 0, 2]));
        assert_eq!(
            DedupEngine::rank_keys((0..10_001).map(|_| Ok::<_, ()>(vec![])))
                .unwrap()
                .len(),
            10_001
        );
        assert!(DedupEngine::rank_keys([Err::<Vec<u16>, _>("JNI error")]).is_err());
    }
    use super::*;

    #[test]
    fn test_dedup_by_content_with_collision_safety() {
        let node1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "pass1", "", "Node1");
        let node2 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "pass1", "", "Node1"); // duplicate
        let node3 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "pass2", "", "Node1"); // different pass

        let res = DedupEngine::dedup_by_content(&[node1, node2, node3]);
        assert_eq!(res.unique_nodes.len(), 2);
        assert_eq!(res.deduplicated_count, 1);
        assert_eq!(res.duplicate_indices, vec![1]);
        assert_eq!(res.unique_nodes[0].password, "pass1");
        assert_eq!(res.unique_nodes[1].password, "pass2");
    }

    #[test]
    fn test_forced_fingerprint_collision_preserves_distinct_content() {
        let first = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "pass1", "", "Node");
        let mut second = first.clone();
        second.password = "pass2".into();
        let nodes = [first.clone(), second.clone(), second, first];
        let result = DedupEngine::dedup_by_content_with_fingerprint(&nodes, |_| Fingerprint(0));
        assert_eq!(result.unique_nodes.len(), 2);
        assert_eq!(result.unique_nodes[0].password, "pass1");
        assert_eq!(result.unique_nodes[1].password, "pass2");
        assert_eq!(result.duplicate_indices, vec![2, 3]);
        assert_eq!(result.deduplicated_count, 2);
    }

    #[test]
    fn test_dedup_by_endpoint_matches_protocols_deduplication() {
        let node1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "aes-128-gcm", "pass1", "", "Node1");
        let node2 = CanonicalNode::new(
            "ss",
            "1.1.1.1",
            8388,
            "chacha20-ietf-poly1305",
            "pass2",
            "",
            "Node2",
        ); // same endpoint
        let node3 = CanonicalNode::new("ss", "1.1.1.2", 8388, "aes-128-gcm", "pass1", "", "Node3"); // different IP

        let res = DedupEngine::dedup_by_endpoint(&[node1, node2, node3]);
        assert_eq!(res.unique_nodes.len(), 2);
        assert_eq!(res.deduplicated_count, 1);
        assert_eq!(res.duplicate_indices, vec![1]);
    }

    #[test]
    fn test_disambiguate_names_matches_raw_updater() {
        let mut nodes = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "", "", "MyNode"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "", "", "MyNode"),
            CanonicalNode::new("ss", "1.1.1.3", 8388, "", "", "", "MyNode"),
            CanonicalNode::new("ss", "1.1.1.4", 8388, "", "", "", "OtherNode"),
        ];

        DedupEngine::disambiguate_names(&mut nodes);
        assert_eq!(nodes[0].name, "MyNode");
        assert_eq!(nodes[1].name, "MyNode (1)");
        assert_eq!(nodes[2].name, "MyNode (2)");
        assert_eq!(nodes[3].name, "OtherNode");
    }

    #[test]
    fn test_dedup_by_endpoint_socks4_vs_socks5_same_family() {
        let node_s4 = CanonicalNode::new("socks4", "1.1.1.1", 1080, "", "", "", "Socks4Node");
        let node_s5 = CanonicalNode::new("socks5", "1.1.1.1", 1080, "", "", "", "Socks5Node");

        let res = DedupEngine::dedup_by_endpoint(&[node_s4, node_s5]);
        assert_eq!(
            res.unique_nodes.len(),
            1,
            "SOCKS4 and SOCKS5 at same endpoint must dedup"
        );
        assert_eq!(res.deduplicated_count, 1);
        assert_eq!(res.duplicate_indices, vec![1]);
        assert_eq!(res.unique_nodes[0].protocol, "socks4");
    }

    #[test]
    fn test_dedup_by_endpoint_ss_vs_socks_separate_family() {
        let node_ss = CanonicalNode::new("ss", "1.1.1.1", 1080, "aes-128-gcm", "p1", "", "SSNode");
        let node_s5 = CanonicalNode::new("socks5", "1.1.1.1", 1080, "", "", "", "Socks5Node");

        let res = DedupEngine::dedup_by_endpoint(&[node_ss, node_s5]);
        assert_eq!(
            res.unique_nodes.len(),
            2,
            "SS and SOCKS at same endpoint must NOT dedup"
        );
        assert_eq!(res.deduplicated_count, 0);
    }

    #[test]
    fn test_dedup_by_endpoint_hysteria_family() {
        let node_hy1 = CanonicalNode::new("hysteria", "1.1.1.1", 443, "", "", "", "Hy1");
        let node_hy2 = CanonicalNode::new("hysteria2", "1.1.1.1", 443, "", "", "", "Hy2");

        let res = DedupEngine::dedup_by_endpoint(&[node_hy1, node_hy2]);
        assert_eq!(
            res.unique_nodes.len(),
            1,
            "Hysteria1 and Hysteria2 at same endpoint must dedup"
        );
        assert_eq!(res.deduplicated_count, 1);
        assert_eq!(res.duplicate_indices, vec![1]);
    }
}
