use super::url::{parse_url, percent_decode};
use crate::model::{CanonicalNode, ExtraConfig, TlsConfig};

pub fn parse_tuic(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("tuic://") {
        return Err("invalid scheme");
    }

    let normalized = super::candidate_url::normalize(url)?;
    let parsed = parse_url(&normalized)?;
    let port = parsed.port.unwrap_or(443);
    let fragment = parsed.fragment.map(percent_decode).unwrap_or_default();

    let raw_user = percent_decode(parsed.username);
    let raw_pass = percent_decode(parsed.password);

    let (uuid, token) = if raw_user.contains(':') {
        let (u, t) = raw_user.split_once(':').unwrap();
        (u.to_string(), t.to_string())
    } else {
        (raw_user, raw_pass)
    };

    let mut sni = String::new();
    // RawUpdater initializes TuicBean defaults after parsing. Explicit query
    // values, including empty strings, must still override these defaults.
    let mut congestion_control = "cubic".to_string();
    let mut udp_relay_mode = "native".to_string();
    let mut alpn = Vec::new();
    let mut allow_insecure = false;
    let mut disable_sni = false;

    if let Some(query) = parsed.query {
        for (k, value) in super::url::first_query_parameters(query) {
            if let Some(decoded_v) = value {
                match k.as_str() {
                    "sni" => sni = decoded_v,
                    "congestion_control" => congestion_control = decoded_v,
                    "udp_relay_mode" => udp_relay_mode = decoded_v,
                    "alpn" => {
                        alpn = decoded_v.split(',').map(str::to_string).collect();
                    }
                    "allow_insecure" => {
                        allow_insecure = decoded_v == "1";
                    }
                    "disable_sni" => {
                        disable_sni = decoded_v == "1";
                    }
                    _ => {}
                }
            }
        }
    }

    let mut node = CanonicalNode::new("tuic", parsed.host, port, &uuid, &token, "", &fragment);

    node.tls = Some(TlsConfig {
        enabled: true,
        server_name: sni,
        alpn,
        allow_insecure,
        disable_sni,
        ..Default::default()
    });

    node.extra = Some(ExtraConfig {
        congestion_control,
        udp_relay_mode,
        ..Default::default()
    });

    Ok(node)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tuic_basic() {
        let uri = "tuic://my-uuid:my-token@tuic.example.com:8443?congestion_control=bbr&alpn=h3&allow_insecure=1#TuicNode";
        let node = parse_tuic(uri).unwrap();
        assert_eq!(node.protocol, "tuic");
        assert_eq!(node.server, "tuic.example.com");
        assert_eq!(node.port, 8443);
        assert_eq!(node.username, "my-uuid");
        assert_eq!(node.password, "my-token");
        assert_eq!(node.name, "TuicNode");
        let tls = node.tls.unwrap();
        assert!(tls.allow_insecure);
        assert_eq!(tls.alpn, vec!["h3"]);
        let extra = node.extra.unwrap();
        assert_eq!(extra.congestion_control, "bbr");
    }
}
