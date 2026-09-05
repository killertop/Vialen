use super::url::{parse_url, percent_decode};
use crate::model::{CanonicalNode, ExtraConfig, TlsConfig};

pub fn parse_hysteria1(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("hysteria://") {
        return Err("invalid scheme");
    }

    let normalized = super::candidate_url::normalize(url)?;
    let parsed = parse_url(&normalized)?;
    let port = parsed.port.unwrap_or(443);
    let fragment = parsed.fragment.map(percent_decode).unwrap_or_default();

    let mut mport = port.to_string();
    let mut sni = String::new();
    let mut auth_payload = String::new();
    let mut allow_insecure = false;
    let mut up_mbps = 0;
    let mut down_mbps = 0;
    let mut alpn = Vec::new();
    let mut obfs_param = String::new();

    if let Some(query) = parsed.query {
        for (k, value) in super::url::first_query_parameters(query) {
            if let Some(decoded_v) = value {
                match k.as_str() {
                    "mport" => mport = decoded_v,
                    "peer" => sni = decoded_v,
                    "auth" => {
                        if !decoded_v.trim().is_empty() {
                            auth_payload = decoded_v;
                        }
                    }
                    "insecure" => {
                        if decoded_v == "1" || decoded_v == "true" {
                            allow_insecure = true;
                        }
                    }
                    "upmbps" => {
                        if let Ok(val) = decoded_v.parse::<i32>() {
                            up_mbps = val;
                        }
                    }
                    "downmbps" => {
                        if let Ok(val) = decoded_v.parse::<i32>() {
                            down_mbps = val;
                        }
                    }
                    "alpn" => {
                        alpn = decoded_v.split(',').map(str::to_string).collect();
                    }
                    "obfsParam" => obfs_param = decoded_v,
                    "protocol" => {
                        let proto_lower = decoded_v.to_ascii_lowercase();
                        if proto_lower == "faketcp" || proto_lower == "wechat-video" {
                            return Err("unsupported hysteria1 protocol");
                        } else if proto_lower != "udp" {
                            return Err("unknown hysteria1 protocol");
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    let mut node = CanonicalNode::new(
        "hysteria1",
        parsed.host,
        port,
        "",
        &auth_payload,
        &obfs_param,
        &fragment,
    );

    node.tls = Some(TlsConfig {
        enabled: true,
        server_name: sni,
        alpn,
        allow_insecure,
        disable_sni: false,
        reality_public_key: String::new(),
        reality_short_id: String::new(),
        certificates: String::new(),
        utls_fingerprint: String::new(),
    });

    node.extra = Some(ExtraConfig {
        congestion_control: String::new(),
        udp_relay_mode: String::new(),
        obfs_type: "salamander".to_string(),
        obfs_password: obfs_param,
        upload_mbps: up_mbps,
        download_mbps: down_mbps,
        mport,
        auth_payload,
        ..Default::default()
    });

    Ok(node)
}

pub fn parse_hysteria2(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("hysteria2://") && !url.starts_with("hy2://") {
        return Err("invalid scheme");
    }

    let normalized = super::candidate_url::normalize(url)?;
    let parsed = parse_url(&normalized)?;
    let port = parsed.port.unwrap_or(443);
    let fragment = parsed.fragment.map(percent_decode).unwrap_or_default();

    let raw_user = percent_decode(parsed.username);
    let raw_pass = percent_decode(parsed.password);

    let auth_payload = if !raw_pass.trim().is_empty() {
        format!("{}:{}", raw_user, raw_pass)
    } else {
        raw_user.clone()
    };

    let mut mport = port.to_string();
    let mut sni = String::new();
    let mut allow_insecure = false;
    let mut obfs_type = "salamander".to_string();
    let mut obfs_password = String::new();

    if let Some(query) = parsed.query {
        for (k, value) in super::url::first_query_parameters(query) {
            if let Some(decoded_v) = value {
                match k.as_str() {
                    "mport" => mport = decoded_v,
                    "sni" => sni = decoded_v,
                    "insecure" => {
                        if decoded_v == "1" || decoded_v == "true" {
                            allow_insecure = true;
                        }
                    }
                    "obfs" => {
                        if decoded_v.eq_ignore_ascii_case("gecko") {
                            obfs_type = "gecko".to_string();
                        } else if decoded_v.eq_ignore_ascii_case("salamander") {
                            obfs_type = "salamander".to_string();
                        }
                    }
                    "obfs-password" => obfs_password = decoded_v,
                    _ => {}
                }
            }
        }
    }

    let mut node = CanonicalNode::new(
        "hysteria2",
        parsed.host,
        port,
        &raw_user,
        &raw_pass,
        &obfs_type,
        &fragment,
    );

    node.tls = Some(TlsConfig {
        enabled: true,
        server_name: sni,
        alpn: Vec::new(),
        allow_insecure,
        disable_sni: false,
        reality_public_key: String::new(),
        reality_short_id: String::new(),
        certificates: String::new(),
        utls_fingerprint: String::new(),
    });

    node.extra = Some(ExtraConfig {
        congestion_control: String::new(),
        udp_relay_mode: String::new(),
        obfs_type,
        obfs_password,
        upload_mbps: 0,
        download_mbps: 0,
        mport,
        auth_payload,
        ..Default::default()
    });

    Ok(node)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hysteria1_basic() {
        let uri = "hysteria://hy1.example.com:36712?auth=mytoken&peer=sni.hy1.com&insecure=1&upmbps=100&downmbps=200&protocol=udp#Hy1Node";
        let node = parse_hysteria1(uri).unwrap();
        assert_eq!(node.protocol, "hysteria1");
        assert_eq!(node.server, "hy1.example.com");
        assert_eq!(node.port, 36712);
        assert_eq!(node.name, "Hy1Node");
        let tls = node.tls.unwrap();
        assert_eq!(tls.server_name, "sni.hy1.com");
        assert!(tls.allow_insecure);
        let extra = node.extra.unwrap();
        assert_eq!(extra.upload_mbps, 100);
        assert_eq!(extra.download_mbps, 200);
        assert_eq!(extra.auth_payload, "mytoken");
    }

    #[test]
    fn test_hysteria2_basic() {
        let uri = "hysteria2://user:pass@hy2.example.com:443?sni=sni.hy2.com&obfs=salamander&obfs-password=secret#Hy2Node";
        let node = parse_hysteria2(uri).unwrap();
        assert_eq!(node.protocol, "hysteria2");
        assert_eq!(node.server, "hy2.example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.username, "user");
        assert_eq!(node.password, "pass");
        assert_eq!(node.name, "Hy2Node");
        let extra = node.extra.unwrap();
        assert_eq!(extra.auth_payload, "user:pass");
        assert_eq!(extra.obfs_type, "salamander");
        assert_eq!(extra.obfs_password, "secret");
    }
}
