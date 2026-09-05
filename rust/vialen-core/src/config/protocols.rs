use super::{kotlin_space, not_blank};
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Standard {
    pub protocol_type: String,
    pub server: String,
    pub port: i32,
    pub username: String,
    pub password: String,
    pub uuid: String,
    pub encryption: String,
    pub alter_id: i32,
    pub version: i32,
    pub transport_type: String,
    pub host: String,
    pub path: String,
    pub security: String,
    pub sni: String,
    pub alpn: String,
    pub fingerprint: String,
    pub allow_insecure: bool,
    pub reality_key: String,
    pub reality_short_id: String,
    pub ws_early_data: i32,
    pub early_header: String,
    pub certificates: String,
    pub enable_ech: bool,
    pub ech_config: String,
    pub packet_encoding: i32,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Hysteria {
    pub server: String,
    pub protocol_version: i32,
    pub server_ports: String,
    pub auth: String,
    pub auth_type: i32,
    pub protocol: Option<i32>,
    pub obfuscation: String,
    pub sni: String,
    pub alpn: String,
    pub certificate: String,
    pub up: i32,
    pub down: i32,
    pub insecure: bool,
    pub hop_interval: i32,
    pub hop_max: Option<i32>,
    pub bbr: Option<String>,
    pub disable_parrot: Option<bool>,
    pub obfs_type: Option<String>,
    pub obfs_min: Option<i32>,
    pub obfs_max: Option<i32>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct WireGuard {
    pub server: String,
    pub port: i32,
    pub local_address: String,
    pub private_key: String,
    pub public_key: String,
    pub pre_shared_key: String,
    pub mtu: i32,
    pub reserved: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct AnyTLS {
    pub server: String,
    pub port: i32,
    pub password: String,
    pub sni: String,
    pub insecure: bool,
    pub alpn: String,
    pub certificate: String,
    pub fingerprint: String,
    pub ech_config: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Custom {
    pub config: String,
}

pub(crate) fn list(s: &str) -> Vec<&str> {
    s.split([',', '\n'])
        .map(|v| v.trim_matches(kotlin_space))
        .filter(|v| !v.is_empty())
        .collect()
}

/// Kotlin toIntOrNull accepts BMP Unicode decimal digits, signs and leading zeros,
/// but neither whitespace nor supplementary-plane digits (it iterates UTF-16 chars).
pub(crate) fn int(s: &str) -> Option<i32> {
    let (negative, rest) = if let Some(v) = s.strip_prefix('-') {
        (true, v)
    } else {
        (false, s.strip_prefix('+').unwrap_or(s))
    };
    if rest.is_empty() {
        return None;
    }
    const ZEROS: &[u32] = &[
        0x30, 0x660, 0x6f0, 0x7c0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6, 0xc66, 0xce6, 0xd66,
        0xde6, 0xe50, 0xed0, 0xf20, 0x1040, 0x1090, 0x17e0, 0x1810, 0x1946, 0x19d0, 0x1a80, 0x1a90,
        0x1b50, 0x1bb0, 0x1c40, 0x1c50, 0xa620, 0xa8d0, 0xa900, 0xa9d0, 0xa9f0, 0xaa50, 0xabf0,
        0xff10,
    ];
    let mut n = 0_i64;
    for c in rest.chars() {
        let c = c as u32;
        let digit = ZEROS
            .iter()
            .find_map(|z| (c >= *z && c < z + 10).then(|| c - z))?;
        n = n.checked_mul(10)?.checked_add(digit as i64)?;
        if n > i32::MAX as i64 + i64::from(negative) {
            return None;
        }
    }
    Some(if negative { -n } else { n } as i32)
}

fn tls(p: &Standard, global: bool) -> Option<Value> {
    if p.security != "tls" {
        return None;
    }
    let mut t = json!({"enabled":true, "insecure":p.allow_insecure || global});
    if not_blank(&p.sni) {
        t["server_name"] = json!(p.sni);
    }
    if not_blank(&p.alpn) {
        t["alpn"] = json!(list(&p.alpn));
    }
    if not_blank(&p.certificates) {
        t["certificate"] = json!(p.certificates);
    }
    let mut fp = p.fingerprint.as_str();
    if not_blank(&p.reality_key) {
        t["reality"] =
            json!({"enabled":true,"public_key":p.reality_key,"short_id":p.reality_short_id});
        if !not_blank(fp) {
            fp = "chrome";
        }
    }
    if not_blank(fp) {
        t["utls"] = json!({"enabled":true,"fingerprint":fp});
    }
    if p.enable_ech {
        let mut ech = json!({"enabled":true});
        if not_blank(&p.ech_config) {
            // Kotlin lines() recognizes CRLF, CR and LF and retains the final empty line.
            ech["config"] = json!(
                p.ech_config
                    .replace("\r\n", "\n")
                    .replace('\r', "\n")
                    .split('\n')
                    .collect::<Vec<_>>()
            );
        }
        t["ech"] = ech;
    }
    Some(t)
}

fn transport(p: &Standard) -> Option<Value> {
    Some(match p.transport_type.as_str() {
        "ws" => {
            let mut t = json!({"type":"ws", "headers":{}});
            if not_blank(&p.host) {
                t["headers"]["Host"] = json!(p.host);
            }
            if let Some((path, ed)) = p.path.split_once("?ed=") {
                t["path"] = json!(path);
                t["max_early_data"] = json!(int(ed).unwrap_or(2048));
                t["early_data_header_name"] = json!("Sec-WebSocket-Protocol");
            } else {
                t["path"] = json!(if not_blank(&p.path) { &p.path } else { "/" });
            }
            if p.ws_early_data > 0 {
                t["max_early_data"] = json!(p.ws_early_data);
            }
            if not_blank(&p.early_header) {
                t["early_data_header_name"] = json!(p.early_header);
            }
            t
        }
        "http" => {
            let mut t =
                json!({"type":"http", "path": if not_blank(&p.path) { &p.path } else { "/" }});
            if p.security != "tls" {
                t["method"] = json!("GET");
            }
            if not_blank(&p.host) {
                t["host"] = json!(p.host.split(',').collect::<Vec<_>>());
            }
            t
        }
        "quic" => json!({"type":"quic"}),
        "grpc" => json!({"type":"grpc", "service_name":p.path}),
        "httpupgrade" => json!({"type":"httpupgrade", "host":p.host, "path":p.path}),
        _ => return None,
    })
}

pub(super) fn standard(p: Standard, global: bool) -> Result<Value, &'static str> {
    let mut out = json!({"type":p.protocol_type,"server":p.server,"server_port":p.port});
    match p.protocol_type.as_str() {
        "http" => {
            out["username"] = json!(p.username);
            out["password"] = json!(p.password);
        }
        "trojan" => {
            out["password"] = json!(p.password);
        }
        "shadowtls" => {
            out["password"] = json!(p.password);
            out["version"] = json!(p.version);
        }
        "vmess" | "vless" => {
            out["uuid"] = json!(p.uuid);
            if p.protocol_type == "vmess" {
                out["alter_id"] = json!(p.alter_id);
                out["security"] = json!(if not_blank(&p.encryption) {
                    &p.encryption
                } else {
                    "auto"
                });
            } else if not_blank(&p.encryption) && p.encryption != "auto" {
                out["flow"] = json!(p.encryption);
            }
            match p.packet_encoding {
                0 => out["packet_encoding"] = json!(""),
                1 => out["packet_encoding"] = json!("packetaddr"),
                2 => out["packet_encoding"] = json!("xudp"),
                _ => {}
            }
        }
        _ => return Err("INVALID_PROTOCOL"),
    }
    if let Some(t) = tls(&p, global) {
        out["tls"] = t;
    }
    if matches!(p.protocol_type.as_str(), "trojan" | "vmess" | "vless")
        && let Some(t) = transport(&p)
    {
        out["transport"] = t;
    }
    Ok(out)
}

pub(super) fn hysteria(p: Hysteria, global: bool) -> Result<Value, &'static str> {
    if !matches!(p.protocol_version, 1 | 2) {
        return Err("INVALID_HYSTERIA_VERSION");
    }
    if p.protocol_version == 1 && p.protocol.is_some_and(|v| v != 0) {
        return Err("HYSTERIA_PROTOCOL_REMOVED");
    }
    let mut out = json!({"type": if p.protocol_version == 1 { "hysteria" } else { "hysteria2" },
        "server":p.server,"hop_interval":format!("{}s",p.hop_interval),"up_mbps":p.up,"down_mbps":p.down});
    if let Some(port) = int(&p.server_ports) {
        out["server_port"] = json!(port);
    } else {
        out["server_ports"] = json!(
            p.server_ports
                .split(',')
                .map(|s| s.replace('-', ":"))
                .filter(|s| s.split(':').count() == 2)
                .collect::<Vec<_>>()
        );
    }
    let mut t = json!({"enabled":true,"insecure":p.insecure || global});
    if not_blank(&p.sni) {
        t["server_name"] = json!(p.sni);
    }
    if not_blank(&p.certificate) {
        t["certificate"] = json!(p.certificate);
    }
    if p.protocol_version == 1 {
        out["obfs"] = json!(p.obfuscation);
        match p.auth_type {
            1 => out["auth_str"] = json!(p.auth),
            2 => out["auth"] = json!(p.auth),
            _ => {}
        }
        if not_blank(&p.alpn) {
            t["alpn"] = json!(list(&p.alpn));
        }
    } else {
        if let Some(max) = p.hop_max.filter(|v| *v > 0) {
            out["hop_interval_max"] = json!(format!("{max}s"));
        }
        if let Some(bbr) = p
            .bbr
            .filter(|v| not_blank(v) && !v.eq_ignore_ascii_case("default"))
        {
            out["bbr_profile"] = json!(bbr.to_lowercase());
        }
        if p.disable_parrot == Some(true) {
            out["disable_chrome_parrot"] = json!(true);
        }
        if not_blank(&p.obfuscation) {
            let gecko = p.obfs_type.is_some_and(|s| s.eq_ignore_ascii_case("gecko"));
            let mut obfs = json!({"type": if gecko { "gecko" } else { "salamander" }, "password":p.obfuscation});
            if gecko {
                if let Some(min) = p.obfs_min.filter(|v| *v != 512) {
                    obfs["min_packet_size"] = json!(min);
                }
                if let Some(max) = p.obfs_max.filter(|v| *v != 1200) {
                    obfs["max_packet_size"] = json!(max);
                }
            }
            out["obfs"] = obfs;
        }
        out["password"] = json!(p.auth);
        t["alpn"] = json!(["h3"]);
    }
    out["tls"] = t;
    Ok(out)
}

pub(super) fn wireguard(p: WireGuard, _global: bool) -> Result<Value, &'static str> {
    let mut peer = json!({"address":p.server,"port":p.port,"public_key":p.public_key,"allowed_ips":["0.0.0.0/0","::/0"]});
    if not_blank(&p.pre_shared_key) {
        peer["pre_shared_key"] = json!(p.pre_shared_key);
    }
    if not_blank(&p.reserved) {
        let parts = list(&p.reserved);
        if parts.len() == 3 {
            let parsed: Option<Vec<_>> = parts
                .iter()
                .map(|s| int(&s.replace(['[', ']', ' '], "")))
                .collect();
            if let Some(values) = parsed {
                peer["reserved"] = json!(values);
            }
        }
    }
    let mut out = json!({"type":"wireguard","address":list(&p.local_address),"private_key":p.private_key,"peers":[peer]});
    if p.mtu > 0 {
        out["mtu"] = json!(p.mtu);
    }
    Ok(out)
}

pub(super) fn anytls(p: AnyTLS, _global: bool) -> Result<Value, &'static str> {
    let mut t = json!({"enabled":true});
    if not_blank(&p.sni) {
        t["server_name"] = json!(p.sni);
    }
    if p.insecure {
        t["insecure"] = json!(true);
    }
    if not_blank(&p.alpn) {
        t["alpn"] = json!(list(&p.alpn));
    }
    if not_blank(&p.certificate) {
        t["certificate"] = json!(p.certificate);
    }
    if not_blank(&p.fingerprint) {
        t["utls"] = json!({"enabled":true,"fingerprint":p.fingerprint});
    }
    if not_blank(&p.ech_config) {
        t["ech"] = json!({"enabled":true,"config":[p.ech_config]});
    }
    Ok(
        json!({"type":"anytls","server":p.server,"server_port":p.port,"password":p.password,"tls":t}),
    )
}

pub(super) fn custom(p: Custom, _global: bool) -> Result<Value, &'static str> {
    match serde_json::from_str::<Value>(&p.config) {
        Ok(Value::Null) => Ok(json!({})),
        Ok(value) if value.is_object() => Ok(value),
        _ => Err("INVALID_CUSTOM_OUTBOUND"),
    }
}
