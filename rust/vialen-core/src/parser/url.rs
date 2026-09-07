fn hex_byte(high: u8, low: u8) -> Option<u8> {
    fn digit(byte: u8) -> Option<u8> {
        match byte {
            b'0'..=b'9' => Some(byte - b'0'),
            b'a'..=b'f' => Some(byte - b'a' + 10),
            b'A'..=b'F' => Some(byte - b'A' + 10),
            _ => None,
        }
    }
    Some(digit(high)? * 16 + digit(low)?)
}

pub fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Some(val) = hex_byte(bytes[i + 1], bytes[i + 2])
        {
            out.push(val);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn decode_query_param(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Some(val) = hex_byte(bytes[i + 1], bytes[i + 2])
        {
            out.push(val);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn canonicalize_url_host(host: &str) -> String {
    if let Ok(ipv6) = host.parse::<std::net::Ipv6Addr>() {
        ipv6.to_string()
    } else {
        host.to_ascii_lowercase()
    }
}

pub struct ParsedUrl<'a> {
    pub scheme: &'a str,
    pub username: &'a str,
    pub password: &'a str,
    pub host: &'a str,
    pub port: Option<u16>,
    pub path: Option<&'a str>,
    pub query: Option<&'a str>,
    pub fragment: Option<&'a str>,
}

pub fn parse_url(url: &str) -> Result<ParsedUrl<'_>, &'static str> {
    let (scheme, rest) = match url.find("://") {
        Some(idx) => (&url[..idx], &url[idx + 3..]),
        None => return Err("missing scheme"),
    };

    let (before_frag, fragment) = match rest.find('#') {
        Some(idx) => (&rest[..idx], Some(&rest[idx + 1..])),
        None => (rest, None),
    };

    let (before_query, query) = match before_frag.find('?') {
        Some(idx) => (&before_frag[..idx], Some(&before_frag[idx + 1..])),
        None => (before_frag, None),
    };

    let (userinfo, host_port_path) = match before_query.rfind('@') {
        Some(idx) => (Some(&before_query[..idx]), &before_query[idx + 1..]),
        None => (None, before_query),
    };

    let (host_port, path) = match host_port_path.find('/') {
        Some(idx) => (&host_port_path[..idx], Some(&host_port_path[idx..])),
        None => (host_port_path, None),
    };

    let (username, password) = match userinfo {
        Some(info) => match info.find(':') {
            Some(colon) => (&info[..colon], &info[colon + 1..]),
            None => (info, ""),
        },
        None => ("", ""),
    };

    let (host, port) = if host_port.starts_with('[') {
        // IPv6 bracketed
        match host_port.find(']') {
            Some(end_bracket) => {
                let h = &host_port[1..end_bracket];
                let after = &host_port[end_bracket + 1..];
                let p = if let Some(stripped) = after.strip_prefix(':') {
                    let val = stripped.parse::<u16>().map_err(|_| "invalid port")?;
                    if val == 0 {
                        return Err("invalid port");
                    }
                    Some(val)
                } else {
                    None
                };
                (h, p)
            }
            None => return Err("malformed ipv6"),
        }
    } else {
        match host_port.rfind(':') {
            Some(idx) => {
                let h = &host_port[..idx];
                let p = host_port[idx + 1..]
                    .parse::<u16>()
                    .map_err(|_| "invalid port")?;
                if p == 0 {
                    return Err("invalid port");
                }
                (h, Some(p))
            }
            None => (host_port, None),
        }
    };

    Ok(ParsedUrl {
        scheme,
        username,
        password,
        host,
        port,
        path,
        query,
        fragment,
    })
}

/// OkHttp queryParameter semantics: decode names, retain the first occurrence,
/// and distinguish a missing value from an explicitly empty value.
pub fn first_query_parameters(query: &str) -> Vec<(String, Option<String>)> {
    let mut seen = std::collections::HashSet::new();
    query
        .split('&')
        .filter_map(|part| {
            let (name, value) = match part.split_once('=') {
                Some((name, value)) => (name, Some(value)),
                None => (part, None),
            };
            let name = decode_query_param(name);
            if seen.insert(name.clone()) {
                Some((name, value.map(decode_query_param)))
            } else {
                None
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn incomplete_percent_escape_preserves_unicode_without_panicking() {
        for input in ["%中", "%a中", "%🔥", "%é", "节点%", "%2", "%GG"] {
            assert_eq!(super::percent_decode(input), input);
            assert_eq!(super::decode_query_param(input), input);
        }
    }
    use super::*;

    #[test]
    fn test_percent_decode() {
        assert_eq!(percent_decode("hello%20world"), "hello world");
        assert_eq!(percent_decode("%E4%BD%A0%E5%A5%BD"), "你好");
    }

    #[test]
    fn test_parse_url() {
        let u = parse_url("ss://user:pass@127.0.0.1:8388?plugin=obfs#MyNode").unwrap();
        assert_eq!(u.scheme, "ss");
        assert_eq!(u.username, "user");
        assert_eq!(u.password, "pass");
        assert_eq!(u.host, "127.0.0.1");
        assert_eq!(u.port, Some(8388));
        assert_eq!(u.query, Some("plugin=obfs"));
        assert_eq!(u.fragment, Some("MyNode"));
    }

    #[test]
    fn test_query_decoding_plus_and_spaces() {
        // OkHttp queryParameter replaces '+' with ' '
        assert_eq!(decode_query_param("hello+world"), "hello world");
        assert_eq!(decode_query_param("hello%2Bworld"), "hello+world");
        assert_eq!(decode_query_param("a+b+c%20d"), "a b c d");
    }

    #[test]
    fn test_query_decoding_percent_encoded_unicode() {
        // OkHttp decodes UTF-8 percent sequences
        assert_eq!(
            decode_query_param("%E4%BD%A0%E5%A5%BD%2B%E4%B8%96%E7%95%8C"),
            "你好+世界"
        );
        assert_eq!(decode_query_param("%F0%9F%8C%9F+Star"), "🌟 Star");
    }

    #[test]
    fn test_parse_url_bracketed_ipv6() {
        // Bracketed IPv6 with port
        let u1 = parse_url("vless://uuid@[2001:db8::1]:443?type=ws#IPv6Node").unwrap();
        assert_eq!(u1.host, "2001:db8::1");
        assert_eq!(u1.port, Some(443));
        assert_eq!(u1.fragment, Some("IPv6Node"));

        // Bracketed IPv6 without port
        let u2 = parse_url("vless://uuid@[fe80::1ff:fe23:4567:890a]?type=grpc").unwrap();
        assert_eq!(u2.host, "fe80::1ff:fe23:4567:890a");
        assert_eq!(u2.port, None);

        // Bracketed IPv6 with path
        let u3 = parse_url("vless://uuid@[::1]:8080/custom/path?security=tls").unwrap();
        assert_eq!(u3.host, "::1");
        assert_eq!(u3.port, Some(8080));
        assert_eq!(u3.path, Some("/custom/path"));

        // Case preservation (do not lowercase unless source does)
        let u4 = parse_url("vless://uuid@[2001:0DB8:ABCD:0012::1]:443").unwrap();
        assert_eq!(u4.host, "2001:0DB8:ABCD:0012::1");
    }

    #[test]
    fn test_parse_url_query_empty_and_key_only() {
        // Key-only query params, empty values, multiple consecutive delimiters
        let u = parse_url("vless://uuid@example.com:443?tls&key_only=&val=1&&flag").unwrap();
        assert_eq!(u.query, Some("tls&key_only=&val=1&&flag"));

        let mut params = Vec::new();
        if let Some(q) = u.query {
            for param in q.split('&') {
                if param.is_empty() {
                    continue;
                }
                if let Some((k, v)) = param.split_once('=') {
                    params.push((k, decode_query_param(v)));
                } else {
                    // key-only param has no '='
                    params.push((param, String::new()));
                }
            }
        }

        assert_eq!(
            params,
            vec![
                ("tls", String::new()),
                ("key_only", String::new()),
                ("val", "1".to_string()),
                ("flag", String::new()),
            ]
        );
    }
}
