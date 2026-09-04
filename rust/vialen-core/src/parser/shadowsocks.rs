use super::base64::decode_base64_url_safe;
use super::url::{parse_url, percent_decode};

use crate::model::CanonicalNode;
pub type CanonicalProxy = CanonicalNode;

pub fn parse_shadowsocks(url: &str) -> Result<CanonicalProxy, &'static str> {
    if !url.starts_with("ss://") {
        return Err("invalid scheme");
    }

    let before_hash = url.split('#').next().unwrap_or(url);
    let fragment = if let Some(idx) = url.find('#') {
        percent_decode(&url[idx + 1..])
    } else {
        String::new()
    };

    if before_hash.contains('@') {
        // Standard SIP002 or Plain format: ss://[base64(method:password) OR method:password]@host:port/?plugin=...#name
        let parsed = parse_url(url)?;
        let port = parsed.port.ok_or("invalid port")?;

        let mut plugin = String::new();
        if let Some(query) = parsed.query {
            for param in query.split('&') {
                if let Some(("plugin", v)) = param.split_once('=') {
                    plugin = percent_decode(v);
                    break;
                }
            }
        }
        if plugin.starts_with("simple-obfs") {
            plugin = plugin.replacen("simple-obfs", "obfs-local", 1);
        }

        let (method, password) = if !parsed.password.is_empty() {
            // Plain format
            (
                percent_decode(parsed.username),
                percent_decode(parsed.password),
            )
        } else {
            // SIP002 base64 encoded userinfo
            let decoded_bytes =
                decode_base64_url_safe(parsed.username).map_err(|_| "invalid base64 userinfo")?;
            let decoded_str =
                String::from_utf8(decoded_bytes).map_err(|_| "non-utf8 credentials")?;
            match decoded_str.find(':') {
                Some(colon) => (
                    percent_decode(&decoded_str[..colon]),
                    percent_decode(&decoded_str[colon + 1..]),
                ),
                None => return Err("invalid method:password format"),
            }
        };

        Ok(CanonicalNode::new(
            "shadowsocks",
            parsed.host,
            port,
            &method,
            &password,
            &plugin,
            &fragment,
        ))
    } else {
        // Legacy v2rayN format: ss://[base64(method:password@host:port)]#name
        let b64_part = before_hash.strip_prefix("ss://").ok_or("missing prefix")?;
        if b64_part.is_empty() {
            return Err("invalid ss link");
        }
        let decoded_bytes = decode_base64_url_safe(b64_part).map_err(|_| "invalid base64 uri")?;
        let decoded_str = String::from_utf8(decoded_bytes).map_err(|_| "non-utf8 uri")?;

        let full_url = format!("ss://{}", decoded_str);
        let parsed = parse_url(&full_url).map_err(|_| "invalid decoded url")?;
        let port = parsed.port.ok_or("missing port")?;

        Ok(CanonicalNode::new(
            "shadowsocks",
            parsed.host,
            port,
            &percent_decode(parsed.username),
            &percent_decode(parsed.password),
            "",
            &fragment,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sip002_parsing() {
        // ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwaGFzZS1iLXRlc3QtcGFzc3dvcmRAMTkyLjE2OC4wLjE2ODo4Mzg4#PhaseD-Local-SS
        let uri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwaGFzZS1iLXRlc3QtcGFzc3dvcmRAMTkyLjE2OC4wLjE2ODo4Mzg4#PhaseD-Local-SS";
        let proxy = parse_shadowsocks(uri).unwrap();
        assert_eq!(proxy.protocol, "shadowsocks");
        assert_eq!(proxy.server, "192.168.0.168");
        assert_eq!(proxy.port, 8388);
        assert_eq!(proxy.username, "chacha20-ietf-poly1305");
        assert_eq!(proxy.password, "phase-b-test-password");
        assert_eq!(proxy.name, "PhaseD-Local-SS");
    }

    #[test]
    fn test_plain_with_plugin() {
        let uri = "ss://aes-128-gcm:secret@example.com:443?plugin=simple-obfs%3Bobfs%3Dhttp#MyNode";
        let proxy = parse_shadowsocks(uri).unwrap();
        assert_eq!(proxy.server, "example.com");
        assert_eq!(proxy.port, 443);
        assert_eq!(proxy.username, "aes-128-gcm");
        assert_eq!(proxy.password, "secret");
        assert_eq!(proxy.plugin, "obfs-local;obfs=http");
        assert_eq!(proxy.name, "MyNode");
    }

    #[test]
    fn test_legacy_v2rayn() {
        // chacha20-ietf-poly1305:mypass@1.1.1.1:8443
        // base64: Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXBhc3NAMS4xLjEuMTo4NDQz
        let uri = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpteXBhc3NAMS4xLjEuMTo4NDQz#LegacyNode";
        let proxy = parse_shadowsocks(uri).unwrap();
        assert_eq!(proxy.server, "1.1.1.1");
        assert_eq!(proxy.port, 8443);
        assert_eq!(proxy.username, "chacha20-ietf-poly1305");
        assert_eq!(proxy.password, "mypass");
        assert_eq!(proxy.name, "LegacyNode");
    }
}
