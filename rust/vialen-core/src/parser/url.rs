pub fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex_res = u8::from_str_radix(&input[i + 1..i + 3], 16);
            if let Ok(val) = hex_res {
                out.push(val);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub struct ParsedUrl<'a> {
    pub scheme: &'a str,
    pub username: &'a str,
    pub password: &'a str,
    pub host: &'a str,
    pub port: Option<u16>,
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

    let (userinfo, host_port) = match before_query.rfind('@') {
        Some(idx) => (Some(&before_query[..idx]), &before_query[idx + 1..]),
        None => (None, before_query),
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
        query,
        fragment,
    })
}

#[cfg(test)]
mod tests {
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
}
