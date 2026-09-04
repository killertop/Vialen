use super::base64::decode_base64_url_safe;
use super::shadowsocks::CanonicalProxy;
use super::url::{parse_url, percent_decode};

pub fn parse_socks(url: &str) -> Result<CanonicalProxy, &'static str> {
    let protocol = if url.starts_with("socks4a://") {
        "socks4a"
    } else if url.starts_with("socks4://") {
        "socks4"
    } else if url.starts_with("socks5://") || url.starts_with("socks://") {
        "socks5"
    } else {
        return Err("invalid socks scheme");
    };

    let rest = url.split("://").nth(1).unwrap_or("");
    if rest.is_empty() {
        return Err("invalid socks url");
    }

    let parsed = parse_url(url)?;
    let port = parsed.port.ok_or("invalid port")?;
    let fragment = parsed.fragment.map(percent_decode).unwrap_or_default();

    let mut username = percent_decode(parsed.username);
    let mut password = percent_decode(parsed.password);

    // v2rayN fmt: username contains base64(user:pass)
    if password.is_empty() && !username.is_empty() {
        let auth = decode_base64_url_safe(&username)
            .ok()
            .and_then(|b| String::from_utf8(b).ok())
            .and_then(|s| {
                s.find(':')
                    .map(|idx| (s[..idx].to_string(), s[idx + 1..].to_string()))
            });
        if let Some((u, p)) = auth {
            username = u;
            password = p;
        }
    }

    Ok(CanonicalProxy {
        protocol: protocol.to_string(),
        server: parsed.host.to_string(),
        port,
        username,
        password,
        plugin: String::new(),
        name: fragment,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_socks5_plain() {
        let uri = "socks5://admin:123456@10.0.0.1:1080#InternalProxy";
        let proxy = parse_socks(uri).unwrap();
        assert_eq!(proxy.protocol, "socks5");
        assert_eq!(proxy.server, "10.0.0.1");
        assert_eq!(proxy.port, 1080);
        assert_eq!(proxy.username, "admin");
        assert_eq!(proxy.password, "123456");
        assert_eq!(proxy.name, "InternalProxy");
    }
}
