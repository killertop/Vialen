//! Production subscription matching over opaque Kotlin Bean projections.
//! Kotlin owns serialization, local overrides and Room; Rust owns change planning.
use std::collections::{HashMap, HashSet};

pub const CONTENT_CHANGED: i32 = 1;
pub const ORDER_CHANGED: i32 = 2;

#[derive(Debug, PartialEq, Eq)]
pub struct Change {
    pub old_index: Option<usize>,
    pub flags: i32,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    pub changes: Vec<Change>,
    pub removed: Vec<usize>,
}

/// Identity is exact Java display-name UTF-16. Content is class-tagged serialized
/// Bean data with names and locally retained overrides excluded by Kotlin.
pub fn plan(
    old_names: &[Vec<u16>],
    old_content: &[Vec<u8>],
    old_orders: &[i64],
    new_names: &[Vec<u16>],
    new_content: &[Vec<u8>],
) -> Result<Plan, &'static str> {
    if old_names.len() != old_content.len()
        || old_names.len() != old_orders.len()
        || new_names.len() != new_content.len()
    {
        return Err("projection count mismatch");
    }
    // Preserve the production last-row identity choice; remove stale duplicates.
    let mut by_name = HashMap::with_capacity(old_names.len());
    for (index, name) in old_names.iter().enumerate() {
        by_name.insert(name.as_slice(), index);
    }
    let mut seen = HashSet::with_capacity(new_names.len());
    let mut retained = vec![false; old_names.len()];
    let mut changes = Vec::with_capacity(new_names.len());
    for (index, name) in new_names.iter().enumerate() {
        if !seen.insert(name.as_slice()) {
            return Err("incoming names must be unique");
        }
        let old_index = by_name.get(name.as_slice()).copied();
        let flags = if let Some(old) = old_index {
            retained[old] = true;
            let content = if old_content[old] != new_content[index] {
                CONTENT_CHANGED
            } else {
                0
            };
            let order = if old_orders[old] != index as i64 + 1 {
                ORDER_CHANGED
            } else {
                0
            };
            content | order
        } else {
            CONTENT_CHANGED
        };
        changes.push(Change { old_index, flags });
    }
    let removed = retained
        .iter()
        .enumerate()
        .filter_map(|(i, keep)| (!keep).then_some(i))
        .collect();
    Ok(Plan { changes, removed })
}

impl Plan {
    /// [new_count, removed_count, old_index/-1, flags, ..., removed_indices...]
    pub fn encode(&self) -> Result<Vec<i32>, &'static str> {
        let size = self
            .changes
            .len()
            .checked_mul(2)
            .and_then(|n| n.checked_add(self.removed.len()))
            .and_then(|n| n.checked_add(2))
            .ok_or("plan size overflow")?;
        if size > i32::MAX as usize {
            return Err("plan exceeds JNI array size");
        }
        let mut out = Vec::with_capacity(size);
        out.extend([self.changes.len() as i32, self.removed.len() as i32]);
        for change in &self.changes {
            out.push(match change.old_index {
                Some(i) => i32::try_from(i).map_err(|_| "old index overflow")?,
                None => -1,
            });
            out.push(change.flags);
        }
        for &index in &self.removed {
            out.push(i32::try_from(index).map_err(|_| "removed index overflow")?);
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn names(values: &[&str]) -> Vec<Vec<u16>> {
        values.iter().map(|s| s.encode_utf16().collect()).collect()
    }
    fn content(values: &[&str]) -> Vec<Vec<u8>> {
        values.iter().map(|s| s.as_bytes().to_vec()).collect()
    }

    #[test]
    fn mixed_plan_handles_content_and_order_and_last_duplicate() {
        let plan = plan(
            &names(&["A", "A", "B", "removed"]),
            &content(&["stale", "a", "b", "r"]),
            &[1, 9, 2, 4],
            &names(&["B", "A", "new"]),
            &content(&["changed", "a", "n"]),
        )
        .unwrap();
        assert_eq!(
            plan.changes,
            vec![
                Change {
                    old_index: Some(2),
                    flags: 3
                },
                Change {
                    old_index: Some(1),
                    flags: 2
                },
                Change {
                    old_index: None,
                    flags: 1
                }
            ]
        );
        assert_eq!(plan.removed, vec![0, 3]);
        assert_eq!(plan.encode().unwrap(), vec![3, 2, 2, 3, 1, 2, -1, 1, 0, 3]);
    }
    #[test]
    fn empty_unchanged_and_deleted_inputs() {
        assert_eq!(
            plan(&[], &[], &[], &[], &[]).unwrap().encode().unwrap(),
            vec![0, 0]
        );
        assert_eq!(
            plan(
                &names(&["A"]),
                &content(&["a"]),
                &[1],
                &names(&["A"]),
                &content(&["a"])
            )
            .unwrap()
            .changes[0]
                .flags,
            0
        );
        assert_eq!(
            plan(&names(&["A"]), &content(&["a"]), &[1], &[], &[])
                .unwrap()
                .removed,
            vec![0]
        );
    }
    #[test]
    fn malformed_projection_and_duplicate_new_names_fail_closed() {
        assert!(plan(&names(&["A"]), &[], &[1], &[], &[]).is_err());
        assert!(plan(&[], &[], &[], &names(&["A", "A"]), &content(&["a", "b"])).is_err());
    }
    #[test]
    fn exact_utf16_binary_content_and_large_input() {
        let names = vec![vec![0xD800], vec![0xD801]];
        let keys = vec![vec![0, 255], vec![0, 254]];
        let result = plan(&names, &keys, &[1, 2], &names, &keys).unwrap();
        assert!(result.changes.iter().all(|c| c.flags == 0));
        let names: Vec<_> = (0..10001)
            .map(|i| i.to_string().encode_utf16().collect())
            .collect();
        let keys = vec![vec![]; names.len()];
        assert_eq!(
            plan(&[], &[], &[], &names, &keys).unwrap().changes.len(),
            10001
        );
    }
}
