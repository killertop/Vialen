//! HTTPS-style URL normalization for the six candidate parsers only.
//! Keep the already-shipped SS/SOCKS parsing contract independent.
use super::url::percent_decode;

pub fn normalize(url: &str) -> Result<String, &'static str> {
    let clean = url.trim_matches(|c: char| c <= ' ');
    let (scheme, rest) = clean.split_once("://").ok_or("missing scheme")?;
    let end = rest.find(['/', '\\', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..end];
    let (userinfo, address) = authority
        .rsplit_once('@')
        .map_or((None, authority), |(u, a)| (Some(u), a));
    let (host, port) = if let Some(ip) = address.strip_prefix('[') {
        let (ip, suffix) = ip.split_once(']').ok_or("malformed ipv6")?;
        let ip = ip
            .parse::<std::net::Ipv6Addr>()
            .map_err(|_| "malformed ipv6")?;
        if !suffix.is_empty() && !suffix.starts_with(':') {
            return Err("invalid authority");
        }
        (format!("[{ip}]"), suffix.strip_prefix(':'))
    } else {
        let (host, port) = address
            .rsplit_once(':')
            .map_or((address, None), |(h, p)| (h, Some(p)));
        let host = percent_decode(host);
        if host.is_empty()
            || host
                .chars()
                .any(|c| c <= ' ' || "#%/:?@[\\]".contains(c) || c == '\u{7f}')
        {
            return Err("invalid host");
        }
        let host = idna::Config::default()
            .transitional_processing(true)
            .use_std3_ascii_rules(false)
            .verify_dns_length(false)
            .to_ascii(&host)
            .map_err(|_| "invalid host")?
            .to_ascii_lowercase();
        if host.is_empty() || host.split('.').any(|label| label.len() > 63) {
            return Err("invalid host");
        }
        (host, port)
    };
    let mut out = format!("{scheme}://");
    if let Some(userinfo) = userinfo {
        let userinfo: String = userinfo
            .chars()
            .filter(|c| !matches!(c, '\t' | '\n' | '\r'))
            .collect();
        out.push_str(&userinfo.replace('@', "%40"));
        out.push('@');
    }
    out.push_str(&host);
    if let Some(port) = port.filter(|p| !p.is_empty()) {
        if !port.bytes().all(|c| c.is_ascii_digit())
            || port.parse::<u16>().ok().filter(|p| *p > 0).is_none()
        {
            return Err("invalid port");
        }
        out.push(':');
        out.push_str(port);
    }
    let tail: String = rest[end..]
        .chars()
        .filter(|c| !matches!(c, '\t' | '\n' | '\r'))
        .collect();
    let path_end = tail.find(['?', '#']).unwrap_or(tail.len());
    let path = tail[..path_end].replace('\\', "/");
    if !path.is_empty() {
        let mut segments: Vec<&str> = Vec::new();
        let raw: Vec<&str> = path.strip_prefix('/').unwrap_or(&path).split('/').collect();
        for (i, segment) in raw.iter().enumerate() {
            match percent_decode(segment).as_str() {
                "." => {
                    if i + 1 == raw.len() {
                        segments.push("");
                    }
                }
                ".." => {
                    segments.pop();
                    if i + 1 == raw.len() {
                        segments.push("");
                    }
                }
                _ => segments.push(segment),
            }
        }
        out.push('/');
        out.push_str(&segments.join("/").replace('@', "%40"));
    }
    out.push_str(&tail[path_end..]);
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn normalizes_https_endpoint_and_dot_segments() {
        assert_eq!(
            normalize("vless://user@faß.de:/a/%2e%2E/b@c/").unwrap(),
            "vless://user@fass.de/b%40c/"
        );
        assert_eq!(
            normalize("tuic://u@[2001:0DB8::1]").unwrap(),
            "tuic://u@[2001:db8::1]"
        );
    }
    #[test]
    fn rejects_invalid_authority() {
        for uri in [
            "trojan://u@",
            "trojan://u@exa\nmple.com",
            "tuic://u@[bad]",
            "vless://u@[::1]suffix",
            "hy2://u@example.com:0",
            "hy2://u@example.com:+443",
        ] {
            assert!(normalize(uri).is_err(), "{uri}");
        }
        assert!(normalize(&format!("vmess://{}", "a".repeat(64))).is_err());
    }
}
