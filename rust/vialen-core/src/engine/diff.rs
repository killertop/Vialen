use crate::model::key::CanonicalIdentityKey;
use crate::model::node::CanonicalNode;
use std::collections::{HashMap, VecDeque};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NodeUpdate {
    pub old_node: CanonicalNode,
    pub new_node: CanonicalNode,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SubscriptionDiffResult {
    /// Newly introduced nodes not present in old subscription.
    pub added: Vec<CanonicalNode>,
    /// Nodes whose identity matched but content changed (endpoint, credentials, TLS, transport, etc.).
    pub updated: Vec<NodeUpdate>,
    /// Existing nodes that no longer exist in the new subscription.
    pub removed: Vec<CanonicalNode>,
    /// Nodes that exist in both and whose content is 100% identical.
    pub unchanged: Vec<CanonicalNode>,
    /// Unchanged nodes whose sequence position changed.
    pub reordered: Vec<CanonicalNode>,
}

pub struct SubscriptionDiffEngine;

impl SubscriptionDiffEngine {
    /// Computes the diff between old subscription nodes and new subscription nodes.
    /// Identity matching follows Kotlin `RawUpdater.kt` semantics based on display name
    /// (node name if present, else server:port).
    ///
    /// Rules:
    /// - identity match + content key match -> Unchanged (or Reordered if position moved)
    /// - identity match + content key mismatch -> Updated
    /// - only in new -> Added
    /// - only in old -> Removed
    ///
    /// Preserves strict deterministic output order.
    pub fn diff(
        old_nodes: &[CanonicalNode],
        new_nodes: &[CanonicalNode],
    ) -> SubscriptionDiffResult {
        let mut added = Vec::new();
        let mut updated = Vec::new();
        let mut removed = Vec::new();
        let mut unchanged = Vec::new();
        let mut reordered = Vec::new();

        // Map IdentityKey -> List of (old_node_index, CanonicalNode)
        // Stored as lists to handle duplicate identities safely and sequentially.
        let mut old_map: HashMap<CanonicalIdentityKey, VecDeque<(usize, &CanonicalNode)>> =
            HashMap::new();
        for (idx, node) in old_nodes.iter().enumerate() {
            let key = CanonicalIdentityKey::from_display_name(node);
            old_map.entry(key).or_default().push_back((idx, node));
        }

        let mut matched_old_indices = vec![false; old_nodes.len()];

        for (new_idx, new_node) in new_nodes.iter().enumerate() {
            let key = CanonicalIdentityKey::from_display_name(new_node);

            // Every queued entry is unmatched; consume in original order without shifting.
            let matched = old_map.get_mut(&key).and_then(VecDeque::pop_front);

            match matched {
                Some((old_idx, old_node)) => {
                    matched_old_indices[old_idx] = true;
                    let old_content = old_node.content_key();
                    let new_content = new_node.content_key();

                    if old_content == new_content {
                        unchanged.push(new_node.clone());
                        if old_idx != new_idx {
                            reordered.push(new_node.clone());
                        }
                    } else {
                        updated.push(NodeUpdate {
                            old_node: old_node.clone(),
                            new_node: new_node.clone(),
                        });
                    }
                }
                None => {
                    added.push(new_node.clone());
                }
            }
        }

        // Any old nodes not matched were removed
        for (idx, old_node) in old_nodes.iter().enumerate() {
            if !matched_old_indices[idx] {
                removed.push(old_node.clone());
            }
        }

        SubscriptionDiffResult {
            added,
            updated,
            removed,
            unchanged,
            reordered,
        }
    }

    /// Serializes SubscriptionDiffResult into a 5-block length-prefixed framing:
    /// Block 0: added nodes
    /// Block 1: updated pairs [old, new, ...]
    /// Block 2: removed nodes
    /// Block 3: unchanged nodes
    /// Block 4: reordered nodes
    pub fn serialize_diff_result(diff: &SubscriptionDiffResult) -> String {
        use crate::engine::batch::encode_length_prefixed_items;
        use crate::parser::serialize_canonical_node;

        let added_serialized: Vec<String> =
            diff.added.iter().map(serialize_canonical_node).collect();
        let added_refs: Vec<&str> = added_serialized.iter().map(|s| s.as_str()).collect();
        let block0 = encode_length_prefixed_items(&added_refs);

        let mut updated_serialized: Vec<String> = Vec::with_capacity(diff.updated.len() * 2);
        for u in &diff.updated {
            updated_serialized.push(serialize_canonical_node(&u.old_node));
            updated_serialized.push(serialize_canonical_node(&u.new_node));
        }
        let updated_refs: Vec<&str> = updated_serialized.iter().map(|s| s.as_str()).collect();
        let block1 = encode_length_prefixed_items(&updated_refs);

        let removed_serialized: Vec<String> =
            diff.removed.iter().map(serialize_canonical_node).collect();
        let removed_refs: Vec<&str> = removed_serialized.iter().map(|s| s.as_str()).collect();
        let block2 = encode_length_prefixed_items(&removed_refs);

        let unchanged_serialized: Vec<String> = diff
            .unchanged
            .iter()
            .map(serialize_canonical_node)
            .collect();
        let unchanged_refs: Vec<&str> = unchanged_serialized.iter().map(|s| s.as_str()).collect();
        let block3 = encode_length_prefixed_items(&unchanged_refs);

        let reordered_serialized: Vec<String> = diff
            .reordered
            .iter()
            .map(serialize_canonical_node)
            .collect();
        let reordered_refs: Vec<&str> = reordered_serialized.iter().map(|s| s.as_str()).collect();
        let block4 = encode_length_prefixed_items(&reordered_refs);

        let blocks = [
            block0.as_str(),
            block1.as_str(),
            block2.as_str(),
            block3.as_str(),
            block4.as_str(),
        ];
        encode_length_prefixed_items(&blocks)
    }

    /// Production pipeline mode: disambiguate_names -> (optional) dedup_by_endpoint -> diff
    pub fn diff_subscription_pipeline(
        old_nodes: &[CanonicalNode],
        new_nodes: &[CanonicalNode],
        deduplicate_endpoints: bool,
    ) -> SubscriptionDiffResult {
        let mut processed_new = new_nodes.to_vec();
        crate::engine::dedup::DedupEngine::disambiguate_names(&mut processed_new);
        if deduplicate_endpoints {
            let dedup = crate::engine::dedup::DedupEngine::dedup_by_endpoint(&processed_new);
            processed_new = dedup.unique_nodes;
        }
        Self::diff(old_nodes, &processed_new)
    }

    /// Computes diff from two raw length-prefixed URI lists using the production pipeline.
    /// Fail-closed: if ANY input fails to parse, returns explicit ERROR|PARSE_FAILED|side|index|reason.
    pub fn diff_raw_pipeline(old_payload: &str, new_payload: &str, deduplicate: bool) -> String {
        use crate::engine::batch::{
            MAX_BATCH_ITEMS, MAX_PAYLOAD_BYTES, decode_length_prefixed_items_strict,
        };
        use crate::parser::parse_proxy;

        if old_payload.len() > MAX_PAYLOAD_BYTES || new_payload.len() > MAX_PAYLOAD_BYTES {
            return "ERROR|INVALID_INPUT|Payload exceeds maximum 10MB limit".to_string();
        }

        let old_uris = match decode_length_prefixed_items_strict(old_payload) {
            Ok(u) => u,
            Err(err) => {
                return format!("ERROR|INTERNAL_ERROR|Failed decoding old payload: {}", err);
            }
        };
        let new_uris = match decode_length_prefixed_items_strict(new_payload) {
            Ok(u) => u,
            Err(err) => {
                return format!("ERROR|INTERNAL_ERROR|Failed decoding new payload: {}", err);
            }
        };

        if old_uris.len() > MAX_BATCH_ITEMS || new_uris.len() > MAX_BATCH_ITEMS {
            return "ERROR|INVALID_INPUT|Item count exceeds limit".to_string();
        }

        let mut old_nodes = Vec::with_capacity(old_uris.len());
        for (idx, u) in old_uris.iter().enumerate() {
            match parse_proxy(u) {
                Ok(node) => old_nodes.push(node),
                Err(e) => return format!("ERROR|PARSE_FAILED|old|{}|{:?}", idx, e),
            }
        }
        let mut new_nodes = Vec::with_capacity(new_uris.len());
        for (idx, u) in new_uris.iter().enumerate() {
            match parse_proxy(u) {
                Ok(node) => new_nodes.push(node),
                Err(e) => return format!("ERROR|PARSE_FAILED|new|{}|{:?}", idx, e),
            }
        }

        let diff_result = Self::diff_subscription_pipeline(&old_nodes, &new_nodes, deduplicate);
        Self::serialize_diff_result(&diff_result)
    }

    /// Computes diff from two raw length-prefixed URI lists and returns serialized diff.
    /// Preserves exact node identities without pipeline mutations.
    pub fn diff_raw(old_payload: &str, new_payload: &str) -> String {
        use crate::engine::batch::{
            MAX_BATCH_ITEMS, MAX_PAYLOAD_BYTES, decode_length_prefixed_items_strict,
        };
        use crate::parser::parse_proxy;

        if old_payload.len() > MAX_PAYLOAD_BYTES || new_payload.len() > MAX_PAYLOAD_BYTES {
            return "ERROR|INVALID_INPUT|Payload exceeds maximum 10MB limit".to_string();
        }

        let old_uris = match decode_length_prefixed_items_strict(old_payload) {
            Ok(u) => u,
            Err(err) => {
                return format!("ERROR|INTERNAL_ERROR|Failed decoding old payload: {}", err);
            }
        };
        let new_uris = match decode_length_prefixed_items_strict(new_payload) {
            Ok(u) => u,
            Err(err) => {
                return format!("ERROR|INTERNAL_ERROR|Failed decoding new payload: {}", err);
            }
        };

        if old_uris.len() > MAX_BATCH_ITEMS || new_uris.len() > MAX_BATCH_ITEMS {
            return "ERROR|INVALID_INPUT|Item count exceeds limit".to_string();
        }

        let mut old_nodes = Vec::with_capacity(old_uris.len());
        for (idx, u) in old_uris.iter().enumerate() {
            match parse_proxy(u) {
                Ok(node) => old_nodes.push(node),
                Err(e) => return format!("ERROR|PARSE_FAILED|old|{}|{:?}", idx, e),
            }
        }
        let mut new_nodes = Vec::with_capacity(new_uris.len());
        for (idx, u) in new_uris.iter().enumerate() {
            match parse_proxy(u) {
                Ok(node) => new_nodes.push(node),
                Err(e) => return format!("ERROR|PARSE_FAILED|new|{}|{:?}", idx, e),
            }
        }

        let diff_result = Self::diff(&old_nodes, &new_nodes);
        Self::serialize_diff_result(&diff_result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_large_duplicate_identity_queue_preserves_pairing_and_removals() {
        let old: Vec<_> = (0..10_000)
            .map(|i| CanonicalNode::new("ss", "example.com", 8388, "", &i.to_string(), "", "Same"))
            .collect();
        let mut new = old[..9_999].to_vec();
        new[5_000].password = "changed".into();
        let diff = SubscriptionDiffEngine::diff(&old, &new);
        assert!(diff.added.is_empty());
        assert!(diff.reordered.is_empty());
        assert_eq!(diff.unchanged.len(), 9_998);
        assert_eq!(diff.updated.len(), 1);
        assert_eq!(diff.updated[0].old_node, old[5_000]);
        assert_eq!(diff.updated[0].new_node, new[5_000]);
        assert_eq!(diff.removed, vec![old[9_999].clone()]);
        let expected: Vec<_> = new
            .into_iter()
            .enumerate()
            .filter_map(|(i, node)| (i != 5_000).then_some(node))
            .collect();
        assert_eq!(diff.unchanged, expected);
    }

    #[test]
    fn test_diff_empty_old() {
        let new_nodes = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "N1"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p2", "", "N2"),
        ];
        let diff = SubscriptionDiffEngine::diff(&[], &new_nodes);
        assert_eq!(diff.added.len(), 2);
        assert!(diff.updated.is_empty());
        assert!(diff.removed.is_empty());
        assert!(diff.unchanged.is_empty());
    }

    #[test]
    fn test_diff_empty_new() {
        let old_nodes = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "N1"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p2", "", "N2"),
        ];
        let diff = SubscriptionDiffEngine::diff(&old_nodes, &[]);
        assert!(diff.added.is_empty());
        assert!(diff.updated.is_empty());
        assert_eq!(diff.removed.len(), 2);
        assert!(diff.unchanged.is_empty());
    }

    #[test]
    fn test_diff_all_unchanged() {
        let nodes = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "N1"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p2", "", "N2"),
        ];
        let diff = SubscriptionDiffEngine::diff(&nodes, &nodes);
        assert!(diff.added.is_empty());
        assert!(diff.updated.is_empty());
        assert!(diff.removed.is_empty());
        assert_eq!(diff.unchanged.len(), 2);
        assert!(diff.reordered.is_empty());
    }

    #[test]
    fn test_diff_reordered_only() {
        let n1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "N1");
        let n2 = CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p2", "", "N2");

        let old = vec![n1.clone(), n2.clone()];
        let new = vec![n2.clone(), n1.clone()];

        let diff = SubscriptionDiffEngine::diff(&old, &new);
        assert!(diff.added.is_empty());
        assert!(diff.updated.is_empty());
        assert!(diff.removed.is_empty());
        assert_eq!(diff.unchanged.len(), 2);
        assert_eq!(diff.reordered.len(), 2);
    }

    #[test]
    fn test_diff_updated_content() {
        let old_n1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "oldpass", "", "Node1");
        let new_n1 = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "newpass", "", "Node1");

        let diff = SubscriptionDiffEngine::diff(&[old_n1], &[new_n1]);
        assert_eq!(diff.updated.len(), 1);
        assert_eq!(diff.updated[0].old_node.password, "oldpass");
        assert_eq!(diff.updated[0].new_node.password, "newpass");
        assert!(diff.added.is_empty());
        assert!(diff.removed.is_empty());
        assert!(diff.unchanged.is_empty());
    }

    #[test]
    fn test_diff_mixed() {
        let old = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "UnchangedNode"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "oldpass", "", "UpdatedNode"),
            CanonicalNode::new("ss", "1.1.1.3", 8388, "", "p3", "", "RemovedNode"),
        ];
        let new = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "UnchangedNode"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "newpass", "", "UpdatedNode"),
            CanonicalNode::new("ss", "1.1.1.4", 8388, "", "p4", "", "AddedNode"),
        ];

        let diff = SubscriptionDiffEngine::diff(&old, &new);
        assert_eq!(diff.unchanged.len(), 1);
        assert_eq!(diff.unchanged[0].name, "UnchangedNode");
        assert_eq!(diff.updated.len(), 1);
        assert_eq!(diff.updated[0].new_node.name, "UpdatedNode");
        assert_eq!(diff.added.len(), 1);
        assert_eq!(diff.added[0].name, "AddedNode");
        assert_eq!(diff.removed.len(), 1);
        assert_eq!(diff.removed[0].name, "RemovedNode");
    }

    #[test]
    fn test_diff_protocol_change_treated_as_updated() {
        // Aligned with RawUpdater.kt: name matches -> Updated (not Removed + Added)
        let old_ss = CanonicalNode::new("ss", "1.1.1.1", 8388, "", "pass1", "", "Tokyo-01");
        let new_trojan = CanonicalNode::new("trojan", "1.1.1.2", 443, "", "pass2", "", "Tokyo-01");

        let diff = SubscriptionDiffEngine::diff(&[old_ss], &[new_trojan]);
        assert_eq!(diff.updated.len(), 1);
        assert_eq!(diff.updated[0].old_node.protocol, "ss");
        assert_eq!(diff.updated[0].new_node.protocol, "trojan");
        assert!(diff.added.is_empty());
        assert!(diff.removed.is_empty());
        assert!(diff.unchanged.is_empty());
    }

    #[test]
    fn test_diff_duplicate_identities_deterministic() {
        let old = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "Dup"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p2", "", "Dup"),
        ];
        let new = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1_mod", "", "Dup"),
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p2_mod", "", "Dup"),
        ];

        let diff = SubscriptionDiffEngine::diff(&old, &new);
        assert_eq!(diff.updated.len(), 2);
        assert_eq!(diff.updated[0].new_node.password, "p1_mod");
        assert_eq!(diff.updated[1].new_node.password, "p2_mod");
        assert!(diff.added.is_empty());
        assert!(diff.removed.is_empty());
    }

    #[test]
    fn test_diff_raw_fail_closed_old_error() {
        use crate::engine::batch::encode_length_prefixed_items;

        let valid_uri = "ss://chacha20-ietf-poly1305:pass@1.1.1.1:8388#Valid";
        let invalid_uri = "invalid://corrupted-protocol:123";

        let old_framed = encode_length_prefixed_items(&[valid_uri, invalid_uri]);
        let new_framed = encode_length_prefixed_items(&[valid_uri]);

        let res = SubscriptionDiffEngine::diff_raw(&old_framed, &new_framed);
        assert!(res.starts_with("ERROR|PARSE_FAILED|old|1|"), "Got: {}", res);
    }

    #[test]
    fn test_diff_raw_fail_closed_new_error() {
        use crate::engine::batch::encode_length_prefixed_items;

        let valid_uri = "ss://chacha20-ietf-poly1305:pass@1.1.1.1:8388#Valid";
        let invalid_uri = "ss://bad-port:99999";

        let old_framed = encode_length_prefixed_items(&[valid_uri]);
        let new_framed = encode_length_prefixed_items(&[valid_uri, invalid_uri]);

        let res = SubscriptionDiffEngine::diff_raw(&old_framed, &new_framed);
        assert!(res.starts_with("ERROR|PARSE_FAILED|new|1|"), "Got: {}", res);
    }

    #[test]
    fn test_diff_pipeline_with_deduplication() {
        let old = vec![CanonicalNode::new(
            "ss", "1.1.1.1", 8388, "", "p1", "", "Server",
        )];
        // New has duplicate names and duplicate endpoints
        let new = vec![
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p1", "", "Server"),
            CanonicalNode::new("ss", "1.1.1.1", 8388, "", "p2", "", "Server"), // duplicate name and endpoint
            CanonicalNode::new("ss", "1.1.1.2", 8388, "", "p3", "", "Server"), // duplicate name
        ];

        let diff = SubscriptionDiffEngine::diff_subscription_pipeline(&old, &new, true);
        // After disambiguate_names: Server, Server (1), Server (2)
        // After dedup_by_endpoint: Server (1.1.1.1:8388), Server (2) (1.1.1.2:8388) [Server (1) dropped as duplicate endpoint]
        // Compare with old: Server is unchanged, Server (2) is added!
        assert_eq!(diff.unchanged.len(), 1);
        assert_eq!(diff.unchanged[0].name, "Server");
        assert_eq!(diff.added.len(), 1);
        assert_eq!(diff.added[0].name, "Server (2)");
        assert!(diff.removed.is_empty());
        assert!(diff.updated.is_empty());
    }

    // [RUST_UNIT] Rust Contract Tests: Framing, Sections, and Pair Integrity
    #[test]
    fn test_diff_serialization_has_exact_5_sections_and_even_pairs() {
        use crate::engine::batch::decode_length_prefixed_items_strict;
        use crate::engine::diff::NodeUpdate;

        let diff = SubscriptionDiffResult {
            added: vec![CanonicalNode::new(
                "ss", "1.1.1.1", 8388, "", "p1", "", "Added",
            )],
            updated: vec![NodeUpdate {
                old_node: CanonicalNode::new("ss", "2.2.2.1", 8388, "", "old", "", "Updated"),
                new_node: CanonicalNode::new("ss", "2.2.2.1", 8388, "", "new", "", "Updated"),
            }],
            removed: vec![CanonicalNode::new(
                "ss", "3.3.3.1", 8388, "", "p3", "", "Removed",
            )],
            unchanged: vec![CanonicalNode::new(
                "ss",
                "4.4.4.1",
                8388,
                "",
                "p4",
                "",
                "Unchanged",
            )],
            reordered: vec![CanonicalNode::new(
                "ss",
                "5.5.5.1",
                8388,
                "",
                "p5",
                "",
                "Reordered",
            )],
        };

        let serialized = SubscriptionDiffEngine::serialize_diff_result(&diff);
        let sections = decode_length_prefixed_items_strict(&serialized)
            .expect("Top-level envelope must decode strictly");
        assert_eq!(
            sections.len(),
            5,
            "Diff envelope MUST have exactly 5 sections"
        );

        // Section 0: Added
        let added_items = decode_length_prefixed_items_strict(&sections[0]).unwrap();
        assert_eq!(added_items.len(), 1);

        // Section 1: Updated (Must be even pairs)
        let updated_items = decode_length_prefixed_items_strict(&sections[1]).unwrap();
        assert_eq!(updated_items.len(), 2);
        assert_eq!(
            updated_items.len() % 2,
            0,
            "Updated section MUST have even items (pairs)"
        );

        // Section 2: Removed
        let removed_items = decode_length_prefixed_items_strict(&sections[2]).unwrap();
        assert_eq!(removed_items.len(), 1);

        // Section 3: Unchanged
        let unchanged_items = decode_length_prefixed_items_strict(&sections[3]).unwrap();
        assert_eq!(unchanged_items.len(), 1);

        // Section 4: Reordered
        let reordered_items = decode_length_prefixed_items_strict(&sections[4]).unwrap();
        assert_eq!(reordered_items.len(), 1);
    }

    #[test]
    fn test_diff_raw_rejects_malformed_framing_and_bounds() {
        use crate::engine::batch::MAX_PAYLOAD_BYTES;

        // Malformed old framing
        let res_old = SubscriptionDiffEngine::diff_raw("5:hello!", "");
        assert!(res_old.starts_with("ERROR|INTERNAL_ERROR|Failed decoding old payload"));

        // Malformed new framing
        let res_new = SubscriptionDiffEngine::diff_raw("", "+5:hello");
        assert!(res_new.starts_with("ERROR|INTERNAL_ERROR|Failed decoding new payload"));

        // Malformed pipeline framing
        let res_pipe = SubscriptionDiffEngine::diff_raw_pipeline("5a:invalid", "", true);
        assert!(res_pipe.starts_with("ERROR|INTERNAL_ERROR|Failed decoding old payload"));

        // Payload bound rejection
        let oversized = "x".repeat(MAX_PAYLOAD_BYTES + 1);
        let res_bound = SubscriptionDiffEngine::diff_raw(&oversized, "");
        assert!(res_bound.starts_with("ERROR|INVALID_INPUT|Payload exceeds maximum 10MB limit"));
    }
}
