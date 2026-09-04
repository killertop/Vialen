use super::url::{parse_url, percent_decode};
use crate::model::{CanonicalNode, TlsConfig, TransportConfig};

pub fn parse_trojan(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("trojan://") {
        return Err("invalid scheme");
    }

    let parsed = parse_url(url)?;
    let port = parsed.port.ok_or("invalid port")?;
    let fragment = parsed.fragment.map(percent_decode).unwrap_or_default();
    let password = percent_decode(parsed.username);

    let mut peer = String::new();
    let mut sni = String::new();
    let mut host_param = String::new();
    let mut alpn = Vec::new();
    let mut allow_insecure = false;
    let mut reality_pubkey = String::new();
    let mut reality_short_id = String::new();
    let mut certificates = String::new();
    let mut utls_fingerprint = String::new();

    let mut transport_type_param = None;
    let mut header_type_param = None;
    let mut query_path = None;
    let mut service_name = None;
    let mut transport_host_param = None;
    let mut max_early_data = 0;
    let mut early_data_header_name = String::new();
    let mut packet_encoding = 0;

    // Initial path from URL path segments (e.g. /path1/path2 -> path1/path2)
    let url_path = parsed
        .path
        .map(|p| p.trim_start_matches('/').trim_end_matches('/'))
        .unwrap_or("");
    let mut transport_path = if !url_path.is_empty() {
        url_path.to_string()
    } else {
        String::new()
    };

    if let Some(query) = parsed.query {
        for param in query.split('&') {
            if let Some((k, v)) = param.split_once('=') {
                let decoded_v = percent_decode(v);
                match k {
                    "allowInsecure" | "allow_insecure" => {
                        if decoded_v == "1" || decoded_v == "true" {
                            allow_insecure = true;
                        }
                    }
                    "peer" => {
                        if !decoded_v.trim().is_empty() {
                            peer = decoded_v;
                        }
                    }
                    "sni" => {
                        if !decoded_v.trim().is_empty() {
                            sni = decoded_v;
                        }
                    }
                    "host" => {
                        host_param = decoded_v.clone();
                        transport_host_param = Some(decoded_v);
                    }
                    "alpn" => {
                        alpn = decoded_v
                            .split(',')
                            .map(|s| s.trim().to_string())
                            .filter(|s| !s.is_empty())
                            .collect();
                    }
                    "cert" => {
                        certificates = decoded_v;
                    }
                    "pbk" => {
                        reality_pubkey = decoded_v;
                    }
                    "sid" => {
                        reality_short_id = decoded_v;
                    }
                    "type" => {
                        transport_type_param = Some(decoded_v);
                    }
                    "headerType" => {
                        header_type_param = Some(decoded_v);
                    }
                    "path" => {
                        query_path = Some(decoded_v);
                    }
                    "serviceName" => {
                        service_name = Some(decoded_v);
                    }
                    "ed" => {
                        if let Ok(ed) = decoded_v.parse::<i32>() {
                            max_early_data = ed;
                        }
                    }
                    "eh" => {
                        early_data_header_name = decoded_v;
                    }
                    "packetEncoding" => {
                        if decoded_v == "packet" {
                            packet_encoding = 1;
                        } else if decoded_v == "xudp" {
                            packet_encoding = 2;
                        }
                    }
                    "fp" => {
                        utls_fingerprint = decoded_v;
                    }
                    _ => {}
                }
            }
        }
    }

    // Determine effective transport type
    let mut transport_type = transport_type_param.unwrap_or_else(|| "tcp".to_string());
    if transport_type == "h2" || header_type_param.as_deref() == Some("http") {
        transport_type = "http".to_string();
    }

    let mut transport_host = String::new();
    match transport_type.as_str() {
        "http" | "httpupgrade" => {
            if let Some(h) = transport_host_param {
                transport_host = h;
            }
            if let Some(p) = query_path {
                transport_path = p;
            }
        }
        "ws" => {
            if let Some(h) = transport_host_param {
                transport_host = h;
            }
            if let Some(p) = query_path {
                transport_path = p;
            }
        }
        "grpc" => {
            if let Some(s) = service_name {
                transport_path = s;
            }
        }
        _ => {}
    }

    // Determine effective SNI
    // Kotlin parseDuckSoft: sni = url.query("sni"); if (sni.isNullOrBlank()) sni = host;
    // parseTrojan: url.query("peer")?.apply { if (isNotBlank()) sni = peer }
    let effective_sni = if !peer.is_empty() {
        peer
    } else if !sni.is_empty() {
        sni
    } else if !host_param.is_empty() {
        host_param
    } else {
        String::new()
    };

    let mut node = CanonicalNode::new("trojan", parsed.host, port, "", &password, "", &fragment);

    node.tls = Some(TlsConfig {
        enabled: true,
        server_name: effective_sni,
        alpn,
        allow_insecure,
        disable_sni: false,
        reality_public_key: reality_pubkey,
        reality_short_id,
        certificates,
        utls_fingerprint,
    });

    node.transport = Some(TransportConfig {
        transport_type,
        host: transport_host,
        path: transport_path,
        early_data_header_name,
        max_early_data,
        service_name: String::new(),
        packet_encoding,
    });

    Ok(node)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_trojan_basic() {
        let uri =
            "trojan://mypassword@example.com:443?peer=sni.example.com&allowInsecure=1#TrojanNode";
        let node = parse_trojan(uri).unwrap();
        assert_eq!(node.protocol, "trojan");
        assert_eq!(node.server, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.password, "mypassword");
        assert_eq!(node.name, "TrojanNode");
        let tls = node.tls.unwrap();
        assert_eq!(tls.server_name, "sni.example.com");
        assert!(tls.allow_insecure);
    }
}
