use crate::config::protocols::int;
use crate::config::{kotlin_space, not_blank};
use serde_json::{Value, json};

pub(crate) fn is_ip(raw: &str) -> bool {
    let parts: Vec<_> = raw.split('.').collect();
    if parts.len() == 4
        && parts.iter().all(|p| {
            !p.is_empty()
                && p.len() <= 3
                && p.bytes().all(|c| c.is_ascii_digit())
                && p.parse::<u16>().is_ok_and(|v| v <= 255)
        })
    {
        return true;
    }
    let raw = if let Some(r) = raw.strip_prefix('[') {
        r.split(']').next().unwrap_or(r)
    } else {
        raw
    };
    let segment =
        |p: &str| !p.is_empty() && p.len() <= 4 && p.bytes().all(|c| c.is_ascii_hexdigit());
    if let Some((before, after)) = raw.split_once("::") {
        let side =
            |p: &str| p.is_empty() || p.strip_prefix(':').unwrap_or(p).split(':').all(segment);
        side(before) && side(after)
    } else {
        raw.split(':').count() == 8 && raw.split(':').all(segment)
    }
}

fn https(raw: &str) -> Option<(String, u16, String)> {
    let (normalized, path) = crate::parser::candidate_url::normalize_with_path(raw).ok()?;
    let parsed = crate::parser::url::parse_url(&normalized).ok()?;
    let path = if path.is_empty() { "/" } else { &path };
    let mut encoded = String::new();
    for c in path.chars() {
        if c > '\u{7e}' || c <= ' ' || "\"<>^`{}|".contains(c) {
            for b in c.to_string().bytes() {
                encoded.push_str(&format!("%{b:02X}"));
            }
        } else {
            encoded.push(c);
        }
    }
    Some((parsed.host.to_string(), parsed.port.unwrap_or(443), encoded))
}
pub(super) fn remote_host(raw: &str) -> Option<String> {
    let address = raw.split_once("://").map_or(raw, |(_, v)| v);
    https(&format!("https://{address}")).map(|v| v.0)
}

pub(super) fn server(raw: &str, tag: &str, resolver: Option<&str>, detour: Option<&str>) -> Value {
    let raw = raw.trim_matches(kotlin_space);
    let mut out = json!({"tag":tag});
    if raw.eq_ignore_ascii_case("fakeip") {
        out["type"] = json!("fakeip");
        out["inet4_range"] = json!("198.18.0.0/15");
        out["inet6_range"] = json!("fc00::/18");
        return out;
    }
    if let Some(d) = detour {
        out["detour"] = json!(d);
    }
    if raw.eq_ignore_ascii_case("local") {
        out["type"] = json!("local");
        return out;
    }
    if let Some(r) = resolver {
        out["domain_resolver"] = json!(r);
    }
    if raw.to_ascii_lowercase().starts_with("https://") {
        out["type"] = json!("https");
        if let Some((host, port, path)) = https(raw) {
            out["server"] = json!(host);
            if port != 443 {
                out["server_port"] = json!(port);
            }
            if path != "/dns-query" {
                out["path"] = json!(path);
            }
        } else {
            out["server"] = json!(
                raw.strip_prefix("https://")
                    .unwrap_or(raw)
                    .split('/')
                    .next()
                    .unwrap_or("")
            );
        }
        return out;
    }
    let (kind, port_default) = [
        ("tls", 853),
        ("tcp", 53),
        ("quic", 853),
        ("h3", 443),
        ("udp", 53),
    ]
    .into_iter()
    .find(|(k, _)| raw.to_ascii_lowercase().starts_with(&format!("{k}://")))
    .unwrap_or(("udp", 53));
    let source = raw.strip_prefix(&format!("{kind}://")).unwrap_or(raw);
    let host = if let Some(v) = source.strip_prefix('[') {
        v.split(']').next().unwrap_or(v)
    } else {
        source.split(':').next().unwrap_or("")
    };
    out["type"] = json!(kind);
    out["server"] = json!(host);
    if source.contains(':')
        && !source.ends_with(']')
        && let Some(port) =
            int(source.rsplit(':').next().unwrap_or("")).filter(|v| *v != port_default)
    {
        out["server_port"] = json!(port);
    }
    out
}

pub(super) fn addresses(raw: &str) -> Vec<&str> {
    raw.split('\n')
        .map(|s| s.trim_matches(kotlin_space))
        .filter(|s| not_blank(s) && !s.starts_with('#'))
        .collect()
}
