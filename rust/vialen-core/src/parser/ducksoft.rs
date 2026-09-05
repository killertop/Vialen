use super::url::{first_query_parameters, parse_url, percent_decode};
use crate::model::{CanonicalNode, ExtraConfig, TlsConfig, TransportConfig};

pub fn parse(url: &str, protocol: &str) -> Result<CanonicalNode, &'static str> {
    let normalized = super::candidate_url::normalize(url)?;
    let parsed = parse_url(&normalized)?;
    let params = first_query_parameters(parsed.query.unwrap_or(""));
    let get = |key: &str| {
        params
            .iter()
            .find(|(k, _)| k == key)
            .and_then(|(_, v)| v.as_deref())
    };
    let val = |key: &str| get(key).unwrap_or("").to_string();
    let trojan = protocol == "trojan";
    let user = percent_decode(parsed.username);
    let mut node = CanonicalNode::new(
        protocol,
        parsed.host,
        parsed.port.unwrap_or(443),
        if trojan { "" } else { &user },
        if trojan { &user } else { "" },
        "",
        &parsed.fragment.map(percent_decode).unwrap_or_default(),
    );
    let security = get("security")
        .filter(|s| !s.trim().is_empty())
        .unwrap_or(if trojan { "tls" } else { "none" });
    let enabled = matches!(security, "tls" | "reality");
    let insecure = matches!(get("allowInsecure"), Some("1" | "true"));
    let mut sni = if enabled { val("sni") } else { String::new() };
    if enabled
        && sni.trim().is_empty()
        && let Some(host) = get("host")
    {
        sni = host.to_string();
    }
    if trojan && let Some(peer) = get("peer").filter(|p| !p.trim().is_empty()) {
        sni = peer.to_string();
    }
    node.tls = Some(TlsConfig {
        enabled,
        server_name: sni,
        alpn: if enabled {
            get("alpn")
                .map(|s| s.split(',').map(str::to_string).collect())
                .unwrap_or_default()
        } else {
            Vec::new()
        },
        allow_insecure: (enabled || trojan) && insecure,
        disable_sni: false,
        reality_public_key: if enabled { val("pbk") } else { String::new() },
        reality_short_id: if enabled { val("sid") } else { String::new() },
        certificates: if enabled { val("cert") } else { String::new() },
        utls_fingerprint: val("fp"),
    });
    let mut kind = get("type").unwrap_or("tcp");
    if kind == "h2" || get("headerType") == Some("http") {
        kind = "http";
    }
    let raw_path = parsed.path.unwrap_or("").strip_prefix('/').unwrap_or("");
    let mut path = percent_decode(raw_path);
    if !raw_path.contains('/') && path.trim().is_empty() {
        path.clear();
    }
    let mut host = String::new();
    let mut max_early_data = 0;
    let mut early_data_header_name = String::new();
    match kind {
        "ws" | "http" | "httpupgrade" => {
            host = val("host");
            if let Some(p) = get("path") {
                path = p.to_string();
            }
            if kind == "ws"
                && let Some(ed) = get("ed")
            {
                max_early_data = ed.parse::<i32>().map_err(|_| "invalid early data")?;
                early_data_header_name = val("eh");
            }
        }
        "grpc" => {
            if let Some(p) = get("serviceName") {
                path = p.to_string();
            }
        }
        _ => {}
    }
    node.transport = Some(TransportConfig {
        transport_type: kind.to_string(),
        host,
        path,
        max_early_data,
        early_data_header_name,
        service_name: if kind == "grpc" {
            val("serviceName")
        } else {
            String::new()
        },
        packet_encoding: match get("packetEncoding") {
            Some("packet") => 1,
            Some("xudp") => 2,
            _ => 0,
        },
    });
    if !trojan {
        node.extra = Some(ExtraConfig {
            alter_id: if protocol == "vless" { -1 } else { 0 },
            encryption: if protocol == "vless" {
                val("flow")
                    .strip_suffix("-udp443")
                    .unwrap_or(&val("flow"))
                    .to_string()
            } else {
                val("encryption")
            },
            ..Default::default()
        });
    }
    Ok(node)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn transport_gates_early_data_and_tls_fields() {
        let tcp = parse("trojan://pw@example.com?security=none&alpn=h2&cert=C&ed=bad&eh=ignored&peer=peer&allowInsecure=1", "trojan").unwrap();
        let tls = tcp.tls.unwrap();
        assert!(!tls.enabled);
        assert!(tls.allow_insecure);
        assert_eq!(tls.server_name, "peer");
        assert!(tls.alpn.is_empty());
        assert!(tls.certificates.is_empty());
        assert!(tcp.transport.unwrap().early_data_header_name.is_empty());
        assert!(parse("vless://id@example.com?type=ws&ed=bad", "vless").is_err());
        let ws = parse(
            "vmess://id@example.com?type=ws&security=tls&alpn=h2,%20http/1.1,,&eh=ignored",
            "vmess",
        )
        .unwrap();
        assert_eq!(ws.tls.unwrap().alpn.join(","), "h2, http/1.1,,");
        assert!(ws.transport.unwrap().early_data_header_name.is_empty());
    }
}
