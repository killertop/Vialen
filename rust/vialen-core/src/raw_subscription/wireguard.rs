use super::{Node, Result};
use crate::config::{not_blank, protocols::int};
use serde_json::json;
fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}
fn unescape(s: &str) -> Result<String> {
    let mut chars = s.chars();
    let mut out = Vec::<u16>::new();
    while let Some(c) = chars.next() {
        let c = if c == '\\' {
            match chars.next().ok_or("INVALID_INI_ESCAPE")? {
                'u' => {
                    let hex: String = chars.by_ref().take(4).collect();
                    if hex.chars().count() != 4 {
                        return Err("INVALID_INI_ESCAPE");
                    }
                    out.push(u16::from_str_radix(&hex, 16).map_err(|_| "INVALID_INI_ESCAPE")?);
                    continue;
                }
                'n' => '\n',
                'r' => '\r',
                't' => '\t',
                'f' => '\u{c}',
                'b' => '\u{8}',
                c => c,
            }
        } else {
            c
        };
        let mut buffer = [0; 2];
        out.extend_from_slice(c.encode_utf16(&mut buffer));
    }
    String::from_utf16(&out).map_err(|_| "INVALID_INI_UTF16")
}
fn logical_lines(text: &str) -> Vec<String> {
    let mut lines = vec![];
    let mut pending = String::new();
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    for line in normalized.lines() {
        let line = java_trim(line);
        pending.push_str(line);
        if pending.chars().rev().take_while(|c| *c == '\\').count() % 2 == 1 {
            pending.pop();
            continue;
        }
        lines.push(std::mem::take(&mut pending));
    }
    lines
}
pub(super) fn parse(text: &str, file_name: &str) -> Result<Vec<Node>> {
    // ini4j defaults: multiOption=true, multiSection=false. Repeated sections
    // merge into one ordered option multimap and ordinary lookups use last value.
    let mut sections: Vec<(String, Vec<(String, String)>)> = vec![];
    let mut current: Option<usize> = None;
    for line in logical_lines(text) {
        let line = java_trim(&line);
        if line.is_empty() || line.starts_with([';', '#']) {
            continue;
        }
        if let Some(name) = line.strip_prefix('[').and_then(|s| s.strip_suffix(']')) {
            let name = java_trim(name);
            current = Some(
                if let Some(index) = sections.iter().position(|(k, _)| k == name) {
                    index
                } else {
                    sections.push((name.into(), vec![]));
                    sections.len() - 1
                },
            );
            continue;
        }
        let mut escaped = false;
        let split = line
            .char_indices()
            .find_map(|(index, c)| {
                if escaped {
                    escaped = false;
                    return None;
                }
                if c == '\\' {
                    escaped = true;
                    return None;
                }
                if matches!(c, '=' | ':') {
                    Some(index)
                } else {
                    None
                }
            })
            .ok_or("INVALID_WIREGUARD_LINE")?;
        let key = unescape(java_trim(&line[..split]))?;
        if key.is_empty() {
            return Err("INVALID_WIREGUARD_KEY");
        }
        let value = unescape(java_trim(&line[split + 1..]))?;
        let index = current.ok_or("INVALID_WIREGUARD_SECTION")?;
        sections[index].1.push((key, value));
    }
    let iface = &sections
        .iter()
        .find(|(k, _)| k == "Interface")
        .ok_or("MISSING_WIREGUARD_INTERFACE")?
        .1;
    let get = |items: &[(String, String)], key: &str| {
        items
            .iter()
            .rev()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.clone())
    };
    let addresses: Vec<_> = iface
        .iter()
        .filter(|(k, _)| k == "Address")
        .flat_map(|(_, v)| v.split(','))
        .collect();
    if addresses.is_empty() {
        return Err("MISSING_WIREGUARD_ADDRESS");
    }
    // The legacy pre-peer clone serializes the boxed MTU as an int, so missing
    // or nonnumeric MTU rejects this format and enters the normal raw fallback.
    let mtu = get(iface, "MTU")
        .and_then(|s| int(&s))
        .ok_or("INVALID_WIREGUARD_MTU")?;
    let mut out = vec![];
    for (_, peer) in sections.iter().filter(|(k, _)| k == "Peer") {
        let Some(endpoint) = get(peer, "Endpoint") else {
            continue;
        };
        let Some((host, port)) = endpoint.rsplit_once(':') else {
            continue;
        };
        let Some(port) = int(port) else { continue };
        let Some(key) = get(peer, "PublicKey") else {
            continue;
        };
        let mut b = json!({"localAddress":addresses.join("\n"),"privateKey":get(iface,"PrivateKey"),"mtu":mtu,"serverAddress":host,"serverPort":port,"peerPublicKey":key,"peerPreSharedKey":get(peer,"PresharedKey")});
        if not_blank(file_name) {
            b["name"] = json!(file_name.strip_suffix(".conf").unwrap_or(file_name));
        }
        out.push(Node::new("WireGuard", b, true));
    }
    if out.is_empty() {
        return Err("MISSING_WIREGUARD_PEERS");
    }
    Ok(out)
}
