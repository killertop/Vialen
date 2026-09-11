//! Subscription format policy. Platform adapters provide only Android JSON syntax
//! and the legacy Bean codec; network, persistence and mutable settings stay out.
mod clash;
mod json;
mod tokener;
mod wireguard;
use crate::config::kotlin_space;
use crate::parser::{
    candidate_url, parse_proxy, serialize_canonical_node,
    url::{first_query_parameters, parse_url, percent_decode},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
type Result<T> = std::result::Result<T, &'static str>;
#[derive(Serialize)]
pub struct Node {
    kind: String,
    fields: Value,
    initialize: bool,
}
impl Node {
    fn new(kind: &str, fields: Value, initialize: bool) -> Self {
        Self {
            kind: kind.into(),
            fields,
            initialize,
        }
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Input {
    version: u32,
    text: String,
    file_name: String,
    json_syntax: Option<Value>,
    mode: String,
    #[serde(default)]
    invalid_universal: Vec<String>,
}
fn is_ip(s: &str) -> bool {
    crate::full_config::is_ip(s)
}
fn trim(s: &str) -> &str {
    s.trim_matches(kotlin_space)
}
fn http(uri: &str, anytls: bool) -> Result<Node> {
    let normalized = candidate_url::normalize(&if anytls {
        uri.replace("anytls://", "https://")
    } else {
        uri.into()
    })?;
    let p = parse_url(&normalized)?;
    if !anytls && p.path.is_some_and(|p| p != "/") {
        return Err("NOT_HTTP_PROXY");
    }
    let params = first_query_parameters(p.query.unwrap_or(""));
    let get = |key: &str| {
        params
            .iter()
            .find(|(k, _)| k == key)
            .and_then(|(_, v)| v.as_deref())
    };
    let mut b = json!({"serverAddress":p.host,"serverPort":p.port.unwrap_or(if p.scheme=="http"{80}else{443}),"name":p.fragment.map(percent_decode),"sni":get("sni")});
    if anytls {
        b["password"] = json!(percent_decode(p.username));
        b["allowInsecure"] = json!(matches!(get("insecure"), Some("1" | "true")));
        b["utlsFingerprint"] = json!(get("fp"));
    } else {
        b["username"] = json!(percent_decode(p.username));
        b["password"] = json!(percent_decode(p.password));
        b["security"] = json!(if p.scheme == "https" { "tls" } else { "" });
    }
    Ok(Node::new(if anytls { "AnyTLS" } else { "HTTP" }, b, true))
}
fn subscription_url(uri: &str) -> String {
    let mut encoded = String::new();
    for byte in uri.as_bytes() {
        if byte.is_ascii_alphanumeric() || b"-._*".contains(byte) {
            encoded.push(*byte as char);
        } else {
            encoded.push_str(&format!("%{byte:02X}"));
        }
    }
    format!("clash://install-config/?url={encoded}")
}
fn universal(uri: &str) -> Result<Node> {
    let (kind, payload, compressed) = if let Some((prefix, payload)) = uri.split_once('?') {
        (
            prefix.strip_prefix("sn://").unwrap_or(prefix),
            payload,
            true,
        )
    } else {
        let rest = uri.strip_prefix("sn://").unwrap_or(uri);
        let kind = rest.split(':').next().unwrap_or(rest);
        // Kotlin substringAfter(':') twice on the full URI skips scheme + type.
        let payload = rest.split_once(':').map_or(rest, |(_, v)| v);
        (kind, payload, false)
    };
    let data = crate::parser::android_base64::util_decode(payload)?;
    let data = if compressed {
        miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(&data, i32::MAX as usize)
            .map_err(|_| "INVALID_UNIVERSAL_COMPRESSION")?
    } else {
        data
    };
    Ok(Node::new(
        "Universal",
        json!({"link":uri,"type":kind,"data":data}),
        true,
    ))
}
enum Links {
    Nodes(Vec<Node>),
    Subscription(String),
}
fn links(text: &str, invalid_universal: &[String]) -> Links {
    let lines: Vec<_> = text.split('\n').map(trim).collect();
    let tokens: Vec<_> = lines.iter().flat_map(|s| s.split(' ')).collect();
    let first = match links_view(&tokens, invalid_universal) {
        Links::Subscription(link) => return Links::Subscription(link),
        Links::Nodes(nodes) => nodes,
    };
    // Exact ordered equality only: spaces in names and equal-count later views
    // must retain the legacy two-view selection behavior.
    if tokens == lines {
        return Links::Nodes(first);
    }
    match links_view(&lines, invalid_universal) {
        Links::Subscription(link) => Links::Subscription(link),
        Links::Nodes(last) => Links::Nodes(if first.len() > last.len() {
            first
        } else {
            last
        }),
    }
}
fn links_view(inputs: &[&str], invalid_universal: &[String]) -> Links {
    let mut nodes = vec![];
    for &uri in inputs {
        if uri.starts_with("clash://install-config?") || uri.starts_with("sn://subscription?") {
            return Links::Subscription(uri.into());
        }
        if uri.starts_with("sn://") {
            if invalid_universal.iter().any(|v| v == uri) {
                continue;
            }
            if let Ok(node) = universal(uri) {
                nodes.push(node);
            }
        } else if uri.starts_with("http://") || uri.starts_with("https://") {
            match http(uri, false) {
                Ok(n) => nodes.push(n),
                Err(_) => return Links::Subscription(subscription_url(uri)),
            }
        } else if uri.starts_with("anytls://") {
            if let Ok(n) = http(uri, true) {
                nodes.push(n);
            }
        } else if let Ok(node) = parse_proxy(uri) {
            nodes.push(Node::new(
                "Canonical",
                json!({"wire":serialize_canonical_node(&node)}),
                true,
            ));
        }
    }
    Links::Nodes(nodes)
}

fn parse(req: Input) -> Result<Value> {
    if req.version != 1 {
        return Err("UNSUPPORTED_VERSION");
    }
    let text = &req.text;
    if req.mode == "wireguard" {
        return Ok(json!({"nodes":wireguard::parse(text,&req.file_name)?}));
    }
    if req.mode == "json" {
        return Ok(
            json!({"nodes":json::parse(req.json_syntax.as_ref().ok_or("INVALID_JSON_ROOT")?)?}),
        );
    }
    if !matches!(req.mode.as_str(), "raw" | "links") {
        return Err("INVALID_PARSE_MODE");
    }
    if req.mode == "raw" {
        if text.contains("proxies:") {
            let options = serde_saphyr::options! {duplicate_keys:serde_saphyr::DuplicateKeyPolicy::LastWins,legacy_octal_numbers:true,with_snippet:false};
            if let Ok(yaml) = serde_saphyr::from_str_with_options::<Value>(text, options) {
                return Ok(json!({"nodes":clash::parse(yaml)?}));
            }
        } else if text.contains("[Interface]")
            && let Ok(nodes) = wireguard::parse(text, &req.file_name)
        {
            return Ok(json!({"nodes":nodes}));
        }
        if let Ok(v) = tokener::parse(text)
            && let Ok(nodes) = json::parse(&v)
        {
            return Ok(json!({"nodes":nodes}));
        }
        if let Ok(bytes) = crate::parser::android_base64::util_decode(text)
            && let Links::Nodes(nodes) =
                links(&String::from_utf8_lossy(&bytes), &req.invalid_universal)
            && !nodes.is_empty()
        {
            return Ok(json!({"nodes":nodes}));
        }
    }
    match links(text, &req.invalid_universal) {
        Links::Nodes(nodes) => {
            Ok(json!({"nodes":if req.mode=="raw"&&nodes.is_empty(){Value::Null}else{json!(nodes)}}))
        }
        Links::Subscription(link) => Ok(json!({"subscription":link})),
    }
}
pub fn generate(input: &[u8]) -> Vec<u8> {
    serde_json::to_vec(&generate_value(input)).expect("serializable result")
}

pub fn generate_optimized(input: &[u8]) -> Vec<u8> {
    let value = generate_value(input);
    let universal = value["nodes"].as_array().is_some_and(|nodes|
        nodes.iter().any(|node| node["kind"] == "Universal"));
    if universal { crate::subscription_wire::encode(&value) }
    else { serde_json::to_vec(&value).expect("serializable result") }
}

#[cfg(test)]
mod optimized_tests {
    use super::*;
    #[test]
    fn only_universal_uses_binary_and_retry_returns_original_json() {
        for text in ["", "[]", "proxies: [{type: socks5, server: example.com, port: 1080}]", "http://example.com:80"] {
            let input = serde_json::to_vec(&json!({"version":1,"mode":"raw","text":text,"file_name":""})).unwrap();
            assert_eq!(generate(&input), generate_optimized(&input));
        }
        let mut input = json!({"version":1,"mode":"raw","text":"sn://socks:AA==\nhttp://example.com:80","file_name":""});
        let encoded = serde_json::to_vec(&input).unwrap();
        assert!(generate_optimized(&encoded).starts_with(b"VCW1"));
        input["invalid_universal"] = json!(["sn://socks:AA=="]);
        let encoded = serde_json::to_vec(&input).unwrap();
        assert_eq!(generate(&encoded), generate_optimized(&encoded));
    }
}

fn generate_value(input: &[u8]) -> Value {
    let result = serde_json::from_slice::<Input>(input)
        .map_err(|_| "INVALID_SUBSCRIPTION_INPUT")
        .and_then(parse);
    match result {
        Ok(mut v) => {
            v["version"] = json!(1);
            v["status"] = json!("SUCCESS");
            v
        }
        Err(e) => json!({"version":1,"status":"ERROR","error":e}),
    }
}

#[cfg(test)]
mod efficiency_tests {
    use super::*;

    fn value(links: Links) -> Value {
        match links {
            Links::Nodes(nodes) => json!({"nodes": nodes}),
            Links::Subscription(link) => json!({"subscription": link}),
        }
    }

    // Frozen legacy selection policy, deliberately evaluating both views.
    fn legacy(text: &str, invalid: &[String]) -> Links {
        let lines: Vec<_> = text.split('\n').map(trim).collect();
        let tokens: Vec<_> = lines.iter().flat_map(|line| line.split(' ')).collect();
        let first = match links_view(&tokens, invalid) {
            Links::Subscription(link) => return Links::Subscription(link),
            Links::Nodes(nodes) => nodes,
        };
        match links_view(&lines, invalid) {
            Links::Subscription(link) => Links::Subscription(link),
            Links::Nodes(last) => Links::Nodes(if first.len() > last.len() {
                first
            } else {
                last
            }),
        }
    }

    #[test]
    fn exact_view_fastpath_matches_legacy_edge_cases() {
        let cases = [
            "",
            "\n\n",
            "not-a-uri",
            "ss://invalid",
            "sn://socks:AQ==",
            "http://example.com:80#node",
            "http://example.com/path",
            "clash://install-config?url=https://example.com/a",
            "sn://subscription?url=https://example.com/a",
            "anytls://secret@example.com:443#name",
            "http://example.com:80#space in name",
            "http://a:80 http://b:81",
            "http://a:80  http://b:81",
            " \t http://example.com:80#name \r\n\n",
            "http://a:80#one\nhttp://b:81#two",
            "http://a:80#one\nhttps://b/path with spaces",
            "http://a:80#one\ninvalid\nsn://socks:AQ==",
            "http://a:80#one\nsn://subscription?url=x y",
        ];
        for text in cases {
            for invalid in [vec![], vec!["sn://socks:AQ==".to_string()]] {
                assert_eq!(
                    value(links(text, &invalid)),
                    value(legacy(text, &invalid)),
                    "{text:?}"
                );
            }
        }
    }

    #[test]
    fn equal_count_keeps_complete_space_containing_name() {
        let actual = value(links("http://example.com:80#space in name", &[]));
        assert_eq!(actual["nodes"][0]["fields"]["name"], "space in name");
    }
}
