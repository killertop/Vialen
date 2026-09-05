//! Versioned, pure single-outbound generation. No database, Android or Go calls.
pub(crate) mod protocols;
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    version: u32,
    global_allow_insecure: bool,
    profile: Profile,
}

#[derive(Deserialize)]
#[serde(tag = "kind", deny_unknown_fields)]
enum Profile {
    Standard(Box<protocols::Standard>),
    Hysteria(Box<protocols::Hysteria>),
    WireGuard(Box<protocols::WireGuard>),
    AnyTLS(Box<protocols::AnyTLS>),
    Custom(Box<protocols::Custom>),

    Socks {
        server: String,
        port: i32,
        protocol: i32,
        username: String,
        password: String,
    },
    Shadowsocks {
        server: String,
        port: i32,
        method: String,
        password: String,
        plugin: String,
    },
    Tuic {
        server: String,
        port: i32,
        protocol_version: i32,
        uuid: String,
        token: String,
        congestion_controller: String,
        udp_relay_mode: String,
        reduce_rtt: bool,
        sni: String,
        alpn: String,
        ca_text: String,
        disable_sni: bool,
        allow_insecure: bool,
    },
}

// Kotlin Char.isWhitespace = Java isWhitespace || isSpaceChar. Rust's Unicode
// predicate differs at U+0085 and U+001C..U+001F; preserve the existing contract.
pub(crate) fn kotlin_space(c: char) -> bool {
    matches!(c, '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' |
        '\u{00a0}' | '\u{1680}' | '\u{2000}'..='\u{200a}' |
        '\u{2028}' | '\u{2029}' | '\u{202f}' | '\u{205f}' | '\u{3000}')
}

pub(crate) fn not_blank(s: &str) -> bool {
    !s.chars().all(kotlin_space)
}

fn build(request: Request) -> Result<Value, &'static str> {
    if request.version != 1 {
        return Err("UNSUPPORTED_VERSION");
    }
    let outbound = match request.profile {
        Profile::Standard(p) => protocols::standard(*p, request.global_allow_insecure)?,
        Profile::Hysteria(p) => protocols::hysteria(*p, request.global_allow_insecure)?,
        Profile::WireGuard(p) => protocols::wireguard(*p, request.global_allow_insecure)?,
        Profile::AnyTLS(p) => protocols::anytls(*p, request.global_allow_insecure)?,
        Profile::Custom(p) => protocols::custom(*p, request.global_allow_insecure)?,

        Profile::Socks {
            server,
            port,
            protocol,
            username,
            password,
        } => json!({
            "type": "socks", "server": server, "server_port": port,
            "version": match protocol { 0 => "4", 1 => "4a", _ => "5" },
            "username": username, "password": password,
        }),
        Profile::Shadowsocks {
            server,
            port,
            method,
            password,
            plugin,
        } => {
            let mut out = json!({"type":"shadowsocks", "server":server,
                "server_port":port, "method":method, "password":password});
            if not_blank(&plugin) {
                // Kotlin substringAfter returns the whole string when ';' is absent.
                let (name, options) = plugin.split_once(';').unwrap_or((&plugin, &plugin));
                if name != "none" {
                    out["plugin"] = json!(name);
                    out["plugin_opts"] = json!(options);
                }
            }
            out
        }
        Profile::Tuic {
            server,
            port,
            protocol_version,
            uuid,
            token,
            congestion_controller,
            udp_relay_mode,
            reduce_rtt,
            sni,
            alpn,
            ca_text,
            disable_sni,
            allow_insecure,
        } => {
            if protocol_version == 4 {
                return Err("TUIC_V4_REMOVED");
            }
            let mut tls = json!({"enabled":true, "disable_sni":disable_sni,
                "insecure": allow_insecure || request.global_allow_insecure});
            if not_blank(&sni) {
                tls["server_name"] = json!(sni);
            }
            if not_blank(&alpn) {
                tls["alpn"] = json!(
                    alpn.split([',', '\n'])
                        .map(|s| s.trim_matches(kotlin_space))
                        .filter(|s| !s.is_empty())
                        .collect::<Vec<_>>()
                );
            }
            if not_blank(&ca_text) {
                tls["certificate"] = json!(ca_text);
            }
            let mut out = json!({"type":"tuic", "server":server, "server_port":port,
                "uuid":uuid, "password":token, "congestion_control":congestion_controller,
                "zero_rtt_handshake":reduce_rtt, "tls":tls});
            if udp_relay_mode == "quic" {
                out["udp_relay_mode"] = json!("quic");
            }
            out
        }
    };
    Ok(outbound)
}

/// Byte JSON avoids JNI modified-UTF8 framing. Do not put credentials or parser
/// excerpts in errors. Kotlin has an explicit legacy path for unsupported Beans.
pub fn generate(input: &[u8]) -> Vec<u8> {
    let result = serde_json::from_slice::<Request>(input)
        .map_err(|_| "INVALID_INPUT")
        .and_then(build);
    let response = match result {
        Ok(outbound) => json!({"version":1,"status":"SUCCESS","outbound":outbound}),
        Err(error) => json!({"version":1,"status":"ERROR","error":error}),
    };
    // Value contains no non-finite numbers or non-string map keys.
    serde_json::to_vec(&response).expect("serializable config result")
}

pub(crate) fn generate_value(profile: Value, global: bool) -> Result<Value, &'static str> {
    let input = json!({"version":1,"global_allow_insecure":global,"profile":profile});
    build(serde_json::from_value(input).map_err(|_| "INVALID_OUTBOUND_SNAPSHOT")?)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> Value {
        json!({"version":1,"global_allow_insecure":false,"profile":{
            "kind":"Socks","server":"example.org","port":1080,
            "protocol":2,"username":"","password":""}})
    }
    fn run(input: Value) -> Value {
        serde_json::from_slice(&generate(&serde_json::to_vec(&input).unwrap())).unwrap()
    }
    #[test]
    fn strict_version_schema_and_sanitized_errors() {
        assert_eq!(run(request())["status"], "SUCCESS");
        let mut v = request();
        v["version"] = json!(2);
        assert_eq!(run(v)["error"], "UNSUPPORTED_VERSION");
        let mut v = request();
        v["profile"]["unrecognized"] = json!("secret");
        let error = run(v);
        assert_eq!(error["error"], "INVALID_INPUT");
        assert!(!error.to_string().contains("secret"));
        let mut v = request();
        v["profile"].as_object_mut().unwrap().remove("password");
        assert_eq!(run(v)["status"], "ERROR");
        let mut v = request();
        v["profile"]["port"] = json!(2147483648_i64);
        assert_eq!(run(v)["status"], "ERROR");
        assert!(
            String::from_utf8(generate(&[0xff]))
                .unwrap()
                .contains("INVALID_INPUT")
        );
    }
    #[test]
    fn unicode_and_kotlin_whitespace() {
        assert!(!not_blank("\u{001c}\u{00a0}\u{2007}"));
        assert!(not_blank("\u{0085}"));
        let mut v = request();
        v["profile"]["password"] = json!("汉字🦀\"\n\u{0000}");
        assert_eq!(run(v)["outbound"]["password"], "汉字🦀\"\n\u{0000}");
    }
}
