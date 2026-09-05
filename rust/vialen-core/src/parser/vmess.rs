use super::url::{parse_url, percent_decode};
use crate::model::{CanonicalNode, ExtraConfig, TlsConfig, TransportConfig};
use std::collections::HashMap;

/// Minimalist zero-dependency JSON object parser for flat key-value pairs.
/// Parses `{"key": "value", "key2": 123, ...}` handling string escaping and unquoted numbers.
fn parse_flat_json(json_str: &str) -> Result<HashMap<String, String>, &'static str> {
    let trimmed = json_str.trim();
    if !trimmed.starts_with('{') || !trimmed.ends_with('}') {
        return Err("invalid json object");
    }

    let mut map = HashMap::new();
    let chars: Vec<char> = trimmed.chars().collect();
    let len = chars.len();
    let mut i = 1; // skip opening '{'
    let mut outer_closed = false;

    let mut first = true;
    while i < len {
        skip_json_space(&chars, &mut i)?;
        if i >= len {
            break;
        }
        if chars[i] == '}' {
            outer_closed = true;
            i += 1;
            break;
        }
        if !first {
            if !matches!(chars[i], ',' | ';') {
                return Err("expected JSON separator");
            }
            i += 1;
            skip_json_space(&chars, &mut i)?;
            if i >= len || matches!(chars[i], '}' | ',' | ';') {
                return Err("missing JSON key");
            }
        }
        first = false;
        let key = if matches!(chars[i], '"' | '\'') {
            read_json_string(&chars, &mut i)?
        } else {
            read_json_literal(&chars, &mut i)?
        };

        // Expect colon
        skip_json_space(&chars, &mut i)?;
        if i >= len || !matches!(chars[i], ':' | '=') {
            return Err("expected colon in json");
        }
        i += 1; // skip colon
        if chars[i - 1] == '=' && chars.get(i) == Some(&'>') {
            i += 1;
        }

        // Skip whitespace before value
        skip_json_space(&chars, &mut i)?;
        if i >= len {
            return Err("unexpected end of json value");
        }

        let scalar_string = matches!(chars[i], '"' | '\'');
        let structured = matches!(chars[i], '{' | '[');
        if structured
            && matches!(
                key.as_str(),
                "v" | "ps"
                    | "add"
                    | "port"
                    | "id"
                    | "aid"
                    | "scy"
                    | "net"
                    | "type"
                    | "host"
                    | "path"
                    | "tls"
                    | "sni"
                    | "alpn"
                    | "fp"
            )
        {
            return Err("expected scalar JSON field");
        }
        let mut val;
        if scalar_string {
            val = read_json_string(&chars, &mut i)?;
        } else if chars[i] == '{' {
            // Nested object - skip balanced braces while tracking strings
            let mut depth = 0;
            let mut in_str = false;
            let mut str_esc = false;
            while i < len {
                let c = chars[i];
                i += 1;
                if in_str {
                    if str_esc {
                        str_esc = false;
                    } else if c == '\\' {
                        str_esc = true;
                    } else if c == '"' {
                        in_str = false;
                    }
                } else if c == '"' {
                    in_str = true;
                } else if c == '{' {
                    depth += 1;
                } else if c == '}' {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
            }
            if depth != 0 || in_str {
                return Err("unterminated nested object in json");
            }
            val = "{}".to_string();
        } else if chars[i] == '[' {
            // Nested array - skip balanced brackets while tracking strings
            let mut depth = 0;
            let mut in_str = false;
            let mut str_esc = false;
            while i < len {
                let c = chars[i];
                i += 1;
                if in_str {
                    if str_esc {
                        str_esc = false;
                    } else if c == '\\' {
                        str_esc = true;
                    } else if c == '"' {
                        in_str = false;
                    }
                } else if c == '"' {
                    in_str = true;
                } else if c == '[' {
                    depth += 1;
                } else if c == ']' {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
            }
            if depth != 0 || in_str {
                return Err("unterminated nested array in json");
            }
            val = "[]".to_string();
        } else {
            // Number, boolean, or null value
            val = read_json_literal(&chars, &mut i)?;
        }

        if !scalar_string && val == "null" {
            if key == "aid" {
                return Err("null alterId");
            }
            val.clear();
        }
        map.insert(key, val);
    }

    if !outer_closed {
        return Err("unclosed json object");
    }

    // Skip trailing whitespace
    while i < len && chars[i].is_whitespace() {
        i += 1;
    }
    if i < len {
        return Err("trailing data after json object");
    }

    Ok(map)
}

fn skip_json_space(chars: &[char], i: &mut usize) -> Result<(), &'static str> {
    loop {
        while *i < chars.len() && chars[*i].is_whitespace() {
            *i += 1;
        }
        if chars.get(*i) == Some(&'#')
            || (chars.get(*i) == Some(&'/') && chars.get(*i + 1) == Some(&'/'))
        {
            while *i < chars.len() && chars[*i] != '\n' {
                *i += 1;
            }
        } else if chars.get(*i) == Some(&'/') && chars.get(*i + 1) == Some(&'*') {
            *i += 2;
            while *i + 1 < chars.len() && !(chars[*i] == '*' && chars[*i + 1] == '/') {
                *i += 1;
            }
            if *i + 1 >= chars.len() {
                return Err("unclosed JSON comment");
            }
            *i += 2;
        } else {
            return Ok(());
        }
    }
}

fn read_json_literal(chars: &[char], i: &mut usize) -> Result<String, &'static str> {
    let start = *i;
    while *i < chars.len() && !chars[*i].is_whitespace() && !"{}[]:,;=/#\\".contains(chars[*i]) {
        *i += 1;
    }
    if *i == start {
        return Err("missing JSON literal");
    }
    Ok(chars[start..*i].iter().collect())
}

fn read_json_string(chars: &[char], i: &mut usize) -> Result<String, &'static str> {
    let quote = *chars.get(*i).ok_or("expected json string")?;
    if !matches!(quote, '"' | '\'') {
        return Err("expected json string");
    }
    *i += 1;

    let mut out = String::new();
    while *i < chars.len() {
        let c = chars[*i];
        *i += 1;
        match c {
            c if c == quote => return Ok(out),
            '\\' => {
                let escaped = *chars.get(*i).ok_or("unterminated string escape in json")?;
                *i += 1;
                match escaped {
                    '"' | '\'' | '\\' | '/' | '\n' => out.push(escaped),
                    'b' => out.push('\x08'),
                    'f' => out.push('\x0c'),
                    'n' => out.push('\n'),
                    'r' => out.push('\r'),
                    't' => out.push('\t'),
                    'u' => push_json_unicode_escape(chars, i, &mut out)?,
                    _ => return Err("invalid string escape in json"),
                }
            }
            _ => out.push(c),
        }
    }

    Err("unterminated string in json")
}

fn read_hex4(chars: &[char], i: &mut usize) -> Result<u16, &'static str> {
    if *i + 4 > chars.len() {
        return Err("truncated unicode escape in json");
    }
    let mut value = 0u16;
    for c in &chars[*i..*i + 4] {
        let digit = c.to_digit(16).ok_or("invalid unicode escape in json")? as u16;
        value = (value << 4) | digit;
    }
    *i += 4;
    Ok(value)
}

fn push_json_unicode_escape(
    chars: &[char],
    i: &mut usize,
    out: &mut String,
) -> Result<(), &'static str> {
    let first = read_hex4(chars, i)?;

    if (0xD800..=0xDBFF).contains(&first) {
        if chars.get(*i) != Some(&'\\') || chars.get(*i + 1) != Some(&'u') {
            return Err("missing low surrogate in json unicode escape");
        }
        *i += 2;
        let second = read_hex4(chars, i)?;
        if !(0xDC00..=0xDFFF).contains(&second) {
            return Err("invalid low surrogate in json unicode escape");
        }
        let high = u32::from(first) - 0xD800;
        let low = u32::from(second) - 0xDC00;
        let scalar = 0x10000 + ((high << 10) | low);
        let ch = char::from_u32(scalar).ok_or("invalid unicode scalar in json")?;
        out.push(ch);
    } else if (0xDC00..=0xDFFF).contains(&first) {
        return Err("unexpected low surrogate in json unicode escape");
    } else {
        let ch = char::from_u32(u32::from(first)).ok_or("invalid unicode scalar in json")?;
        out.push(ch);
    }

    Ok(())
}

/// Parse V2RayN Base64 JSON format (e.g. vmess://ey...).
fn parse_vmess_v2rayn(b64: &str) -> Result<CanonicalNode, &'static str> {
    let decoded_bytes = super::android_base64::util_decode(b64)?;
    let json_str = std::str::from_utf8(&decoded_bytes).map_err(|_| "invalid utf8 in vmess json")?;

    let map = parse_flat_json(json_str)?;

    let add = map.get("add").map(|s| s.as_str()).unwrap_or("");
    let port_str = map.get("port").map(|s| s.as_str()).unwrap_or("");
    let id = map.get("id").map(|s| s.as_str()).unwrap_or("");
    let net = map.get("net").map(|s| s.as_str()).unwrap_or("");

    // Kotlin validation: add, port, id, net must all be non-empty
    if add.is_empty() || port_str.is_empty() || id.is_empty() || net.is_empty() {
        return Err("invalid VmessQRCode: missing required fields");
    }

    let port = port_str.parse::<u16>().map_err(|_| "invalid port")?;
    if port == 0 {
        return Err("invalid port");
    }

    let ps = map.get("ps").cloned().unwrap_or_default();
    let aid = map
        .get("aid")
        .and_then(|s| s.parse::<i32>().ok())
        .unwrap_or(0);
    let scy = map.get("scy").cloned().unwrap_or_default();

    let header_type = map.get("type").map(|s| s.as_str()).unwrap_or("");
    let mut transport_type = net.to_string();
    if transport_type == "tcp" && header_type == "http" {
        transport_type = "http".to_string();
    }

    let host = map.get("host").cloned().unwrap_or_default();
    let path = map.get("path").cloned().unwrap_or_default();
    let tls_val = map.get("tls").map(|s| s.as_str()).unwrap_or("");
    let tls_enabled = tls_val == "tls" || tls_val == "reality";

    let sni_val = map.get("sni").cloned().unwrap_or_default();
    let effective_sni = if !sni_val.trim().is_empty() {
        sni_val
    } else if !host.is_empty() {
        host.clone()
    } else {
        String::new()
    };

    let alpn = map
        .get("alpn")
        .map(|s| s.split(',').map(str::to_string).collect())
        .unwrap_or_default();

    let fp = map.get("fp").cloned().unwrap_or_default();

    let mut node = CanonicalNode::new("vmess", add, port, id, "", "", &ps);

    node.tls = Some(TlsConfig {
        enabled: tls_enabled,
        server_name: if tls_enabled {
            effective_sni
        } else {
            String::new()
        },
        alpn: if tls_enabled { alpn } else { Vec::new() },
        allow_insecure: false,
        disable_sni: false,
        reality_public_key: String::new(),
        reality_short_id: String::new(),
        certificates: String::new(),
        utls_fingerprint: if tls_enabled { fp } else { String::new() },
    });

    node.transport = Some(TransportConfig {
        transport_type,
        host,
        path,
        early_data_header_name: String::new(),
        max_early_data: 0,
        service_name: String::new(),
        packet_encoding: 0,
    });

    node.extra = Some(ExtraConfig {
        alter_id: aid,
        encryption: scy,
        ..Default::default()
    });

    Ok(node)
}

/// Parse DuckSoft / Standard URL format for VMess.
fn parse_vmess_ducksoft(url: &str) -> Result<CanonicalNode, &'static str> {
    super::ducksoft::parse(url, "vmess")
}
fn parse_vmess_csv(csv: &str) -> Result<CanonicalNode, &'static str> {
    let args: Vec<&str> = csv.split(',').collect();
    if args.len() < 5 {
        return Err("invalid csv format: less than 5 fields");
    }

    let server_address = args[1];
    let port = args[2].parse::<u16>().map_err(|_| "invalid port")?;
    if port == 0 {
        return Err("invalid port");
    }
    let encryption = args[3].to_string();
    let uuid = args[4].replace('"', "");

    let mut tls_enabled = false;
    let mut host = String::new();
    let mut transport_type = "tcp".to_string();
    let mut path = String::new();

    for it in &args[5..] {
        if *it == "over-tls=true" {
            tls_enabled = true;
        } else if let Some(h) = it.strip_prefix("tls-host=") {
            host = h.to_string();
        } else if let Some(obfs) = it.strip_prefix("obfs=") {
            transport_type = obfs.to_string();
        } else if it.starts_with("obfs-path=") || it.contains("Host:") {
            let p = it.split_once("obfs-path=\"").map_or(*it, |(_, rest)| rest);
            path = p.split_once("\"obfs").map_or(p, |(p, _)| p).to_string();
            let h = it.split_once("Host:").map_or(*it, |(_, rest)| rest);
            host = h.split_once('[').map_or(h, |(h, _)| h).to_string();
        }
    }

    let mut node = CanonicalNode::new("vmess", server_address, port, &uuid, "", "", "");

    node.tls = Some(TlsConfig {
        enabled: tls_enabled,
        server_name: String::new(),
        alpn: Vec::new(),
        allow_insecure: false,
        disable_sni: false,
        reality_public_key: String::new(),
        reality_short_id: String::new(),
        certificates: String::new(),
        utls_fingerprint: String::new(),
    });

    node.transport = Some(TransportConfig {
        transport_type,
        host,
        path,
        early_data_header_name: String::new(),
        max_early_data: 0,
        service_name: String::new(),
        packet_encoding: 0,
    });

    node.extra = Some(ExtraConfig {
        alter_id: 0,
        encryption,
        ..Default::default()
    });

    Ok(node)
}

/// Parse Kitsunebi format (e.g. vmess://<base64>?remarks=...&alterId=...&path=...&tls=...&obfs=...&obfsParam=...)
fn parse_vmess_kitsunebi(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("vmess://") {
        return Err("invalid scheme");
    }
    let raw = &url["vmess://".len()..];
    let (b64_part, query_str) = match raw.split_once('?') {
        Some((b, q)) => (b, Some(q)),
        None => (raw, None),
    };

    let decoded_bytes = super::android_base64::ng_decode(b64_part)?;
    let decoded =
        std::str::from_utf8(&decoded_bytes).map_err(|_| "invalid utf-8 in kitsunebi payload")?;

    let (creds, srv) = decoded.split_once('@').ok_or("invalid kitsunebi format")?;
    if srv.contains('@') {
        return Err("invalid kitsunebi format");
    }
    let (encryption, uuid) = creds.split_once(':').ok_or("invalid kitsunebi format")?;
    if uuid.contains(':') {
        return Err("invalid kitsunebi format");
    }
    let (server_address, port_str) = srv.split_once(':').ok_or("invalid kitsunebi format")?;
    // NGUtil uses the second colon-delimited token and returns zero on bad int.
    // Delay endpoint rejection until after query parsing, which can throw and
    // trigger the production URL fallback instead.
    let port = port_str
        .split(':')
        .next()
        .unwrap_or("")
        .parse::<u16>()
        .ok()
        .filter(|p| *p > 0);

    let mut name = String::new();
    let mut alter_id = 0;
    let mut path = String::new();
    let mut tls_enabled = false;
    let mut allow_insecure = false;
    let mut transport_type = "tcp".to_string();
    let mut host = String::new();
    let mut sni = String::new();

    if let Some(query) = query_str {
        let clean_query: String = query
            .split('#')
            .next()
            .unwrap_or("")
            .chars()
            .filter(|c| !matches!(c, '\t' | '\n' | '\r'))
            .collect();
        let mut obfs_param = String::new();
        for (k, value) in super::url::first_query_parameters(&clean_query) {
            let Some(decoded_v) = value else { continue };
            match k.as_str() {
                "remarks" => name = decoded_v,
                "alterId" => {
                    alter_id = decoded_v.parse::<i32>().map_err(|_| "invalid alterId")?;
                }
                "path" => path = decoded_v,
                "tls" => tls_enabled = true,
                "allowInsecure" => {
                    if decoded_v == "1" || decoded_v == "true" {
                        allow_insecure = true;
                    }
                }
                "obfs" => {
                    transport_type = decoded_v.replace("websocket", "ws").replace("none", "tcp");
                }
                "obfsParam" => {
                    obfs_param = decoded_v;
                }
                _ => {}
            }
        }
        if transport_type == "ws" && !obfs_param.is_empty() {
            if obfs_param.starts_with('{') {
                let fields = parse_flat_json(&obfs_param)?;
                if let Some(h) = fields.get("Host").cloned() {
                    host = h;
                }
            } else if tls_enabled {
                sni = obfs_param;
            }
        }
    }

    let mut node = CanonicalNode::new(
        "vmess",
        server_address,
        port.ok_or("invalid port")?,
        uuid,
        "",
        "",
        &name,
    );

    node.tls = Some(TlsConfig {
        enabled: tls_enabled,
        server_name: sni,
        alpn: Vec::new(),
        allow_insecure,
        disable_sni: false,
        reality_public_key: String::new(),
        reality_short_id: String::new(),
        certificates: String::new(),
        utls_fingerprint: String::new(),
    });

    node.transport = Some(TransportConfig {
        transport_type,
        host,
        path,
        early_data_header_name: String::new(),
        max_early_data: 0,
        service_name: String::new(),
        packet_encoding: 0,
    });

    node.extra = Some(ExtraConfig {
        alter_id,
        encryption: encryption.to_string(),
        ..Default::default()
    });

    Ok(node)
}

/// Parse v2fly issue 26 URL format: vmess://protocol:uuid-aid@host:port/?params#name
pub(super) fn parse_vmess_v2fly(url: &str) -> Result<CanonicalNode, &'static str> {
    let normalized = super::candidate_url::normalize(url)?;
    let parsed = parse_url(&normalized)?;
    let port = parsed.port.unwrap_or(443);
    if port == 0 {
        return Err("invalid port");
    }
    let fragment = parsed.fragment.map(percent_decode).unwrap_or_default();
    let username = percent_decode(parsed.username);
    let password = percent_decode(parsed.password);

    if password.is_empty() {
        return Err("missing password in v2fly format");
    }

    let (uuid, alter_id) = if let Some((u, aid_str)) = password.rsplit_once('-') {
        let aid = aid_str.parse::<i32>().map_err(|_| "invalid alterId")?;
        (u.to_string(), aid)
    } else {
        (
            password.clone(),
            password.parse::<i32>().map_err(|_| "invalid alterId")?,
        )
    };

    let mut protocol = username.clone();
    let mut tls_enabled = false;
    let mut sni = String::new();

    if protocol.ends_with("+tls") {
        tls_enabled = true;
        protocol = protocol[..protocol.len() - 4].to_string();
    }

    let mut path = String::new();
    let mut host = String::new();

    if let Some(query) = parsed.query {
        for (k, value) in super::url::first_query_parameters(query) {
            if let Some(decoded_v) = value {
                match k.as_str() {
                    "tlsServerName" if tls_enabled && !decoded_v.trim().is_empty() => {
                        sni = decoded_v;
                    }
                    "path" if matches!(protocol.as_str(), "http" | "ws" | "httpupgrade") => {
                        path = decoded_v;
                    }
                    "host" if matches!(protocol.as_str(), "http" | "ws" | "httpupgrade") => {
                        if protocol == "http" {
                            host = decoded_v.split('|').collect::<Vec<_>>().join(",");
                        } else {
                            host = decoded_v;
                        }
                    }
                    "serviceName" if protocol == "grpc" => {
                        path = decoded_v;
                    }
                    _ => {}
                }
            }
        }
    }

    let mut node = CanonicalNode::new(
        "vmess",
        &parsed.host.to_ascii_lowercase(),
        port,
        &uuid,
        "",
        "",
        &fragment,
    );

    node.tls = Some(TlsConfig {
        enabled: tls_enabled,
        server_name: sni,
        alpn: Vec::new(),
        allow_insecure: false,
        disable_sni: false,
        reality_public_key: String::new(),
        reality_short_id: String::new(),
        certificates: String::new(),
        utls_fingerprint: String::new(),
    });

    node.transport = Some(TransportConfig {
        transport_type: username,
        host,
        path,
        early_data_header_name: String::new(),
        max_early_data: 0,
        service_name: String::new(),
        packet_encoding: 0,
    });

    node.extra = Some(ExtraConfig {
        alter_id,
        encryption: String::new(),
        ..Default::default()
    });

    Ok(node)
}

pub fn parse_vmess(url: &str) -> Result<CanonicalNode, &'static str> {
    let payload = url.strip_prefix("vmess://").ok_or("invalid scheme")?;
    if !url.contains('?') {
        if let Ok(csv) = super::android_base64::util_decode(payload)
            .and_then(|b| String::from_utf8(b).map_err(|_| "invalid utf8"))
            && csv.contains("= vmess")
            && let Ok(node) = parse_vmess_csv(&csv)
        {
            return Ok(node);
        }
        match parse_vmess_v2rayn(payload) {
            Ok(node) => return Ok(node),
            // Kotlin returns a bean (not a thrown format error) with a null/zero
            // or out-of-range JSON port. Canonical endpoints reject that bean.
            Err("invalid port") => return Err("invalid port"),
            Err(_) => {}
        }
    }
    match parse_vmess_kitsunebi(url) {
        Ok(node) => return Ok(node),
        Err("invalid port") => return Err("invalid port"),
        Err(_) => {}
    }
    let normalized = super::candidate_url::normalize(url)?;
    let parsed = parse_url(&normalized)?;
    if !percent_decode(parsed.password).trim().is_empty() {
        parse_vmess_v2fly(url)
    } else {
        parse_vmess_ducksoft(url)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_flat_json() {
        let json = r#"{"v":"2","ps":"MyNode","add":"1.2.3.4","port":443,"id":"my-uuid","aid":0,"net":"ws","path":"/ws","tls":"tls"}"#;
        let map = parse_flat_json(json).unwrap();
        assert_eq!(map.get("v").unwrap(), "2");
        assert_eq!(map.get("ps").unwrap(), "MyNode");
        assert_eq!(map.get("add").unwrap(), "1.2.3.4");
        assert_eq!(map.get("port").unwrap(), "443");
        assert_eq!(map.get("id").unwrap(), "my-uuid");
        assert_eq!(map.get("aid").unwrap(), "0");
        assert_eq!(map.get("net").unwrap(), "ws");
        assert_eq!(map.get("path").unwrap(), "/ws");
        assert_eq!(map.get("tls").unwrap(), "tls");
    }

    #[test]
    fn test_parse_flat_json_unicode_escape_surrogate_pair() {
        let json = r#"{"ps":"Escaped \uD83D\uDE80 Node","add":"1.2.3.4","port":"443","id":"uuid","net":"ws"}"#;
        let map = parse_flat_json(json).unwrap();
        assert_eq!(map.get("ps").unwrap(), "Escaped \u{1F680} Node");
    }

    #[test]
    fn test_vmess_v2rayn_roundtrip() {
        // {"v":"2","ps":"TestNode","add":"192.168.1.100","port":10443,"id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":0,"scy":"auto","net":"ws","type":"none","host":"example.com","path":"/path","tls":"tls","sni":"sni.example.com"}
        let b64 = "eyJ2IjoiMiIsInBzIjoiVGVzdE5vZGUiLCJhZGQiOiIxOTIuMTY4LjEuMTAwIiwicG9ydCI6MTA0NDMsImlkIjoiYjgzMTM4MWQtNjMyNC00ZDUzLWFkNGYtOGNkYTQ4YjMwODExIiwiYWlkIjowLCJzY3kiOiJhdXRvIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJleGFtcGxlLmNvbSIsInBhdGgiOiIvcGF0aCIsInRscyI6InRscyIsInNuaSI6InNuaS5leGFtcGxlLmNvbSJ9";
        let uri = format!("vmess://{}", b64);
        let node = parse_vmess(&uri).unwrap();
        assert_eq!(node.protocol, "vmess");
        assert_eq!(node.server, "192.168.1.100");
        assert_eq!(node.port, 10443);
        assert_eq!(node.username, "b831381d-6324-4d53-ad4f-8cda48b30811");
        assert_eq!(node.name, "TestNode");
        assert_eq!(node.extra.as_ref().unwrap().alter_id, 0);
        assert_eq!(node.extra.as_ref().unwrap().encryption, "auto");
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "ws");
        assert_eq!(node.transport.as_ref().unwrap().host, "example.com");
        assert_eq!(node.transport.as_ref().unwrap().path, "/path");
        assert!(node.tls.as_ref().unwrap().enabled);
        assert_eq!(node.tls.as_ref().unwrap().server_name, "sni.example.com");
    }

    #[test]
    fn test_vmess_ducksoft_roundtrip() {
        let uri = "vmess://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=ws&security=tls&path=/ws&host=wshost.com&encryption=chacha20-poly1305#DuckNode";
        let node = parse_vmess(uri).unwrap();
        assert_eq!(node.protocol, "vmess");
        assert_eq!(node.server, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.username, "b831381d-6324-4d53-ad4f-8cda48b30811");
        assert_eq!(node.name, "DuckNode");
        assert_eq!(node.extra.as_ref().unwrap().encryption, "chacha20-poly1305");
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "ws");
        assert_eq!(node.transport.as_ref().unwrap().host, "wshost.com");
        assert_eq!(node.transport.as_ref().unwrap().path, "/ws");
        assert!(node.tls.as_ref().unwrap().enabled);
    }

    #[test]
    fn test_parse_flat_json_boundary_cases() {
        // Unterminated string in value
        assert!(parse_flat_json(r#"{"add": "1.2.3.4"#).is_err());
        // Unterminated string in key
        assert!(parse_flat_json(r#"{"add: "1.2.3.4"}"#).is_err());
        // Nested object skipped properly
        let nested =
            r#"{"add":"1.2.3.4","extra":{"k":"v","n":2},"port":443,"id":"uuid","net":"ws"}"#;
        let map = parse_flat_json(nested).unwrap();
        assert_eq!(map.get("add").unwrap(), "1.2.3.4");
        assert_eq!(map.get("port").unwrap(), "443");
        // Nested array skipped properly
        let arr = r#"{"add":"1.2.3.4","tags":["a","b"],"port":443,"id":"uuid","net":"ws"}"#;
        let map_arr = parse_flat_json(arr).unwrap();
        assert_eq!(map_arr.get("add").unwrap(), "1.2.3.4");
        // Trailing garbage rejected
        assert!(parse_flat_json(r#"{"add":"1.2.3.4"} trailing"#).is_err());
        // Unterminated nested object rejected
        assert!(parse_flat_json(r#"{"add":"1.2.3.4","extra":{"k":"v"}"#).is_err());
    }

    fn simple_b64(input: &str) -> String {
        const CHARS: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let bytes = input.as_bytes();
        let mut out = String::new();
        let mut i = 0;
        while i < bytes.len() {
            let b0 = bytes[i];
            let b1 = if i + 1 < bytes.len() { bytes[i + 1] } else { 0 };
            let b2 = if i + 2 < bytes.len() { bytes[i + 2] } else { 0 };

            out.push(CHARS[(b0 >> 2) as usize] as char);
            out.push(CHARS[(((b0 & 3) << 4) | (b1 >> 4)) as usize] as char);
            if i + 1 < bytes.len() {
                out.push(CHARS[(((b1 & 15) << 2) | (b2 >> 6)) as usize] as char);
            } else {
                out.push('=');
            }
            if i + 2 < bytes.len() {
                out.push(CHARS[(b2 & 63) as usize] as char);
            } else {
                out.push('=');
            }
            i += 3;
        }
        out
    }

    #[test]
    fn test_vmess_v2rayn_add_casing_and_required_fields() {
        // Casing of add is preserved as in JSON
        let json = r#"{"v":"2","ps":"Node","add":"Node-01.EXAMPLE.com","port":10443,"id":"b831381d-6324-4d53-ad4f-8cda48b30811","net":"ws"}"#;
        let b64 = simple_b64(json);
        let node = parse_vmess(&format!("vmess://{}", b64)).unwrap();
        assert_eq!(node.server, "Node-01.EXAMPLE.com");

        // Missing required field "add" -> Error
        let no_add = r#"{"v":"2","port":10443,"id":"uuid","net":"ws"}"#;
        let b64_no_add = simple_b64(no_add);
        assert!(parse_vmess_v2rayn(&b64_no_add).is_err());

        // Uppercase "ADD" -> Error because "add" is missing
        let upper_add = r#"{"v":"2","ADD":"1.1.1.1","port":10443,"id":"uuid","net":"ws"}"#;
        let b64_upper = simple_b64(upper_add);
        assert!(parse_vmess_v2rayn(&b64_upper).is_err());

        // Missing "net" -> Error
        let no_net = r#"{"v":"2","add":"1.1.1.1","port":10443,"id":"uuid"}"#;
        let b64_no_net = simple_b64(no_net);
        assert!(parse_vmess_v2rayn(&b64_no_net).is_err());
    }

    #[test]
    fn test_vmess_csv() {
        let csv = r#"remarks = vmess,1.2.3.4,443,auto,"b831381d-6324-4d53-ad4f-8cda48b30811",over-tls=true,tls-host=example.com,obfs=websocket,obfs-path="/path"obfs,Host:example.com["#;
        let b64 = simple_b64(csv);
        let uri = format!("vmess://{}", b64);
        let node = parse_vmess(&uri).unwrap();
        assert_eq!(node.protocol, "vmess");
        assert_eq!(node.server, "1.2.3.4");
        assert_eq!(node.port, 443);
        assert_eq!(node.username, "b831381d-6324-4d53-ad4f-8cda48b30811");
        assert_eq!(node.extra.as_ref().unwrap().encryption, "auto");
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "websocket");
        assert_eq!(node.transport.as_ref().unwrap().path, "Host:example.com[");
        assert_eq!(node.transport.as_ref().unwrap().host, "example.com");
        assert!(node.tls.as_ref().unwrap().enabled);
    }

    #[test]
    fn test_vmess_kitsunebi() {
        let creds = "auto:5af5d0ec-6ea0-3c43-93db-ca3008503bdb@183.232.56.161:1202";
        let b64 = simple_b64(creds);
        let uri = format!(
            "vmess://{}?remarks=JP-Node&alterId=0&path=/v2ray&obfs=websocket&tls=1&obfsParam=%7B%22Host%22:%22wshost.com%22%7D",
            b64
        );
        let node = parse_vmess(&uri).unwrap();
        assert_eq!(node.protocol, "vmess");
        assert_eq!(node.server, "183.232.56.161");
        assert_eq!(node.port, 1202);
        assert_eq!(node.username, "5af5d0ec-6ea0-3c43-93db-ca3008503bdb");
        assert_eq!(node.name, "JP-Node");
        assert_eq!(node.extra.as_ref().unwrap().encryption, "auto");
        assert_eq!(node.extra.as_ref().unwrap().alter_id, 0);
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "ws");
        assert_eq!(node.transport.as_ref().unwrap().path, "/v2ray");
        assert_eq!(node.transport.as_ref().unwrap().host, "wshost.com");
        assert!(node.tls.as_ref().unwrap().enabled);
    }

    #[test]
    fn test_vmess_v2fly() {
        let uri = "vmess://ws+tls:b831381d-6324-4d53-ad4f-8cda48b30811-16@example.com:443/?path=/ws&host=example.com&tlsServerName=sni.example.com#V2FlyNode";
        let node = parse_vmess(uri).unwrap();
        assert_eq!(node.protocol, "vmess");
        assert_eq!(node.server, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.username, "b831381d-6324-4d53-ad4f-8cda48b30811");
        assert_eq!(node.name, "V2FlyNode");
        assert_eq!(node.extra.as_ref().unwrap().alter_id, 16);
        assert_eq!(node.extra.as_ref().unwrap().encryption, "");
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "ws+tls");
        assert_eq!(node.transport.as_ref().unwrap().path, "/ws");
        assert_eq!(node.transport.as_ref().unwrap().host, "example.com");
        assert!(node.tls.as_ref().unwrap().enabled);
        assert_eq!(node.tls.as_ref().unwrap().server_name, "sni.example.com");
    }
}
