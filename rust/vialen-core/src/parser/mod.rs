pub mod base64;
pub mod shadowsocks;
pub mod socks;
pub mod subscription;
pub mod url;

pub use shadowsocks::{CanonicalProxy, parse_shadowsocks};
pub use socks::parse_socks;
pub use subscription::decode_subscription_lines;

fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

pub fn parse_proxy_to_canonical(uri: &str) -> String {
    let result = if uri.starts_with("ss://") {
        parse_shadowsocks(uri)
    } else if uri.starts_with("socks://")
        || uri.starts_with("socks4://")
        || uri.starts_with("socks4a://")
        || uri.starts_with("socks5://")
    {
        parse_socks(uri)
    } else {
        Err("unsupported protocol")
    };

    match result {
        Ok(proxy) => {
            let port_str = proxy.port.to_string();
            format!(
                "SUCCESS\n{}:{}{}:{}{}:{}{}:{}{}:{}{}:{}{}:{}",
                utf16_len(&proxy.protocol),
                proxy.protocol,
                utf16_len(&proxy.server),
                proxy.server,
                utf16_len(&port_str),
                port_str,
                utf16_len(&proxy.username),
                proxy.username,
                utf16_len(&proxy.password),
                proxy.password,
                utf16_len(&proxy.plugin),
                proxy.plugin,
                utf16_len(&proxy.name),
                proxy.name,
            )
        }
        Err(err) => {
            let category = match err {
                "invalid scheme" | "unsupported protocol" => "INVALID_SCHEME",
                "missing port" | "invalid port" => "INVALID_PORT",
                "invalid base64 userinfo"
                | "invalid base64 uri"
                | "invalid base64 length"
                | "invalid base64 character"
                | "invalid base64 padding" => "INVALID_BASE64",
                _ => "INVALID_URI",
            };
            format!("{}\n{}:{}", category, utf16_len(err), err)
        }
    }
}
