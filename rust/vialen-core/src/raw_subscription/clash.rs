use super::{Node, Result};
use crate::config::{not_blank, protocols::int};
use serde_json::{Value, json};

// Java Map/List.toString is part of the accepted YAML scalar coercion contract.
pub(super) fn text(v: &Value) -> String {
    match v {
        Value::String(v) => v.clone(),
        Value::Array(a) => format!("[{}]", a.iter().map(text).collect::<Vec<_>>().join(", ")),
        Value::Object(o) => format!(
            "{{{}}}",
            o.iter()
                .map(|(k, v)| format!("{k}={}", text(v)))
                .collect::<Vec<_>>()
                .join(", ")
        ),
        _ => v.to_string(),
    }
}
fn scalar(v: &Value) -> Value {
    if v.is_null() {
        Value::Null
    } else {
        json!(text(v))
    }
}
fn join(v: &Value) -> Value {
    v.as_array()
        .map(|a| json!(a.iter().map(text).collect::<Vec<_>>().join("\n")))
        .unwrap_or(Value::Null)
}
fn number(v: &Value) -> Value {
    int(&text(v)).map_or(Value::Null, |n| json!(n))
}
fn required_number(v: &Value) -> Result<Value> {
    int(&text(v))
        .map(|n| json!(n))
        .ok_or("INVALID_CLASH_NUMBER")
}
fn is_true(v: &Value) -> bool {
    text(v) == "true"
}
fn map(v: &Value) -> impl Iterator<Item = (&String, &Value)> {
    v.as_object().into_iter().flat_map(|o| o.iter())
}
fn blank(v: &Value) -> bool {
    v.is_null() || !not_blank(&text(v))
}

pub(super) fn parse(root: Value) -> Result<Vec<Node>> {
    let proxies = root
        .get("proxies")
        .and_then(Value::as_array)
        .ok_or("INVALID_CLASH_PROXIES")?;
    let global = root
        .get("global-client-fingerprint")
        .map(text)
        .unwrap_or_default();
    let mut result = Vec::new();
    for proxy in proxies {
        let ty = proxy["type"].as_str().ok_or("INVALID_CLASH_TYPE")?;
        let kind = match ty {
            "socks5" => "SOCKS",
            "http" => "HTTP",
            "ss" => "Shadowsocks",
            "vmess" | "vless" => "VMess",
            "trojan" => "Trojan",
            "anytls" => "AnyTLS",
            "hysteria" | "hysteria2" => "Hysteria",
            "tuic" => "TUIC",
            _ => continue,
        };
        let mut b = json!({});
        match ty {
            "socks5" | "http" | "ss" => {
                b["serverAddress"] = json!(proxy["server"].as_str().ok_or("INVALID_CLASH_SERVER")?);
                b["serverPort"] = required_number(&proxy["port"])?;
                b["password"] = scalar(&proxy["password"]);
                b["name"] = scalar(&proxy["name"]);
                if ty == "ss" {
                    let cipher = proxy["cipher"].as_str().ok_or("INVALID_CLASH_CIPHER")?;
                    b["method"] = json!(if cipher == "dummy" { "none" } else { cipher });
                    let mut parts: Vec<String> = vec![];
                    if proxy.get("plugin").is_some() {
                        let opts = proxy["plugin-opts"]
                            .as_object()
                            .ok_or("INVALID_CLASH_PLUGIN")?;
                        let option = |key: &str| {
                            opts.get(key)
                                .filter(|v| !v.is_null())
                                .map(text)
                                .unwrap_or_default()
                        };
                        match proxy["plugin"].as_str() {
                            Some("obfs") => parts.extend([
                                "obfs-local".into(),
                                format!("obfs={}", option("mode")),
                                format!("obfs-host={}", option("host")),
                            ]),
                            Some("v2ray-plugin") => {
                                parts.extend([
                                    "v2ray-plugin".into(),
                                    format!("mode={}", option("mode")),
                                ]);
                                if option("mode") == "true" {
                                    parts.push("tls".into());
                                }
                                parts.extend([
                                    format!("host={}", option("host")),
                                    format!("path={}", option("path")),
                                ]);
                                if option("mux") == "true" {
                                    parts.push("mux=8".into());
                                }
                            }
                            _ => {}
                        }
                    }
                    b["plugin"] = json!(parts.join(";"));
                } else {
                    b["username"] = scalar(&proxy["username"]);
                    if ty == "http" {
                        b["security"] = json!(if is_true(&proxy["tls"]) { "tls" } else { "" });
                        b["sni"] = scalar(&proxy["sni"]);
                        b["allowInsecure"] = json!(is_true(&proxy["skip-cert-verify"]));
                    }
                }
            }
            "vmess" | "vless" | "trojan" => {
                if proxy["server"].is_null() || number(&proxy["port"]).is_null() {
                    continue;
                }
                b["serverAddress"] = scalar(&proxy["server"]);
                b["serverPort"] = number(&proxy["port"]);
                if ty == "vless" {
                    b["alterId"] = json!(-1);
                    b["packetEncoding"] = json!(2);
                }
                if ty == "trojan" {
                    b["security"] = json!("tls");
                }
                for (k, v) in map(proxy) {
                    match k.as_str() {
                        "name" => b["name"] = scalar(v),
                        "password" if ty == "trojan" => b["password"] = scalar(v),
                        "uuid" if ty != "trojan" => b["uuid"] = scalar(v),
                        "alterId" if ty != "trojan" && b["alterId"] != -1 => {
                            b["alterId"] = number(v)
                        }
                        "cipher" if ty != "trojan" && b["alterId"] != -1 => {
                            b["encryption"] = v.as_str().map_or(Value::Null, |s| json!(s))
                        }
                        "flow"
                            if ty != "trojan"
                                && b["alterId"] == -1
                                && v.as_str().is_some_and(|v| v.contains("xtls-rprx-vision")) =>
                        {
                            b["encryption"] = json!("xtls-rprx-vision")
                        }
                        "packet-encoding" if ty != "trojan" => {
                            b["packetEncoding"] = json!(match v.as_str() {
                                Some("packetaddr") => 1,
                                Some("xudp") => 2,
                                _ => 0,
                            })
                        }
                        "tls" if ty != "trojan" => {
                            b["security"] =
                                json!(if v.as_bool() == Some(true) { "tls" } else { "" })
                        }
                        "servername" | "sni" => b["sni"] = scalar(v),
                        "alpn" => b["alpn"] = join(v),
                        "skip-cert-verify" => b["allowInsecure"] = json!(v.as_bool() == Some(true)),
                        "client-fingerprint" => {
                            b["utlsFingerprint"] =
                                json!(v.as_str().ok_or("INVALID_CLASH_FINGERPRINT")?)
                        }
                        "reality-opts" => {
                            for (k, v) in map(v) {
                                b["security"] = json!("tls");
                                match k.as_str() {
                                    "public-key" => b["realityPubKey"] = scalar(v),
                                    "short-id" => b["realityShortId"] = scalar(v),
                                    _ => {}
                                }
                            }
                        }
                        "network" => match v.as_str() {
                            Some("h2" | "http") => b["type"] = json!("http"),
                            Some("ws" | "grpc") => b["type"] = v.clone(),
                            _ => {}
                        },
                        "ws-opts" => {
                            for (k, v) in map(v) {
                                match k.as_str() {
                                    "headers" => {
                                        for (k, v) in map(v) {
                                            if k.to_lowercase() == "host" {
                                                b["host"] = scalar(v);
                                            }
                                        }
                                    }
                                    "path" => b["path"] = scalar(v),
                                    "max-early-data" => b["wsMaxEarlyData"] = number(v),
                                    "early-data-header-name" => {
                                        b["earlyDataHeaderName"] = scalar(v)
                                    }
                                    "v2ray-http-upgrade" if v.as_bool() == Some(true) => {
                                        b["type"] = json!("httpupgrade")
                                    }
                                    _ => {}
                                }
                            }
                        }
                        "h2-opts" => {
                            for (k, v) in map(v) {
                                match k.as_str() {
                                    "host" => b["host"] = join(v),
                                    "path" => b["path"] = scalar(v),
                                    _ => {}
                                }
                            }
                        }
                        "http-opts" => {
                            for (k, v) in map(v) {
                                match k.as_str() {
                                    "path" => b["path"] = join(v),
                                    "headers" => {
                                        for (k, v) in map(v) {
                                            if k.to_lowercase() == "host" {
                                                if !v.is_array() {
                                                    return Err("INVALID_CLASH_HEADERS");
                                                }
                                                b["host"] = join(v);
                                            }
                                        }
                                    }
                                    _ => {}
                                }
                            }
                        }
                        "grpc-opts" => {
                            for (k, v) in map(v) {
                                if k == "grpc-service-name" {
                                    b["path"] = scalar(v);
                                }
                            }
                        }
                        "smux" => {
                            for (k, v) in map(v) {
                                match k.as_str() {
                                    "enabled" => b["enableMux"] = json!(is_true(v)),
                                    "max-streams" => b["muxConcurrency"] = required_number(v)?,
                                    "padding" => b["muxPadding"] = json!(is_true(v)),
                                    _ => {}
                                }
                            }
                        }
                        "ech-opts" => {
                            for (k, v) in map(v) {
                                if k == "enable" {
                                    b["enableECH"] = json!(is_true(v));
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            "anytls" | "hysteria" | "hysteria2" | "tuic" => {
                let hy = ty.starts_with("hysteria");
                let mut hop = String::new();
                let mut ip = String::new();
                if hy {
                    b["protocolVersion"] = json!(if ty == "hysteria" { 1 } else { 2 });
                }
                for (k, v) in map(proxy) {
                    if v.is_null() {
                        continue;
                    }
                    let k = k.replace('_', "-");
                    match k.as_str() {
                        "name" => b["name"] = scalar(v),
                        "server" => {
                            if ty != "tuic" && !v.is_string() {
                                return Err("INVALID_CLASH_SERVER");
                            }
                            b["serverAddress"] = scalar(v);
                        }
                        "port" => {
                            if hy {
                                b["serverPorts"] = scalar(v);
                            } else {
                                b["serverPort"] = required_number(v)?;
                            }
                        }
                        "ports" if hy => hop = text(v),
                        "password" => {
                            b[if ty == "tuic" {
                                "token"
                            } else if ty == "hysteria2" {
                                "authPayload"
                            } else if ty == "anytls" {
                                "password"
                            } else {
                                continue;
                            }] = scalar(v)
                        }
                        "sni" => b["sni"] = scalar(v),
                        "skip-cert-verify" => b["allowInsecure"] = json!(is_true(v)),
                        "client-fingerprint" if ty == "anytls" => {
                            b["utlsFingerprint"] =
                                json!(v.as_str().ok_or("INVALID_CLASH_FINGERPRINT")?)
                        }
                        "alpn" if ty != "hysteria2" => {
                            b["alpn"] = join(v);
                            if ty == "hysteria" && b["alpn"].is_null() {
                                b["alpn"] = json!("h3");
                            }
                        }
                        "obfs" if ty == "hysteria" => b["obfuscation"] = scalar(v),
                        "obfs-password" if ty == "hysteria2" => b["obfuscation"] = scalar(v),
                        "auth-str" if ty == "hysteria" => {
                            b["authPayloadType"] = json!(1);
                            b["authPayload"] = scalar(v);
                        }
                        "up" | "down" if hy => {
                            b[if k == "up" {
                                "uploadMbps"
                            } else {
                                "downloadMbps"
                            }] = json!(
                                int(text(v).split(' ').next().unwrap_or(""))
                                    .unwrap_or(if ty == "hysteria" { 100 } else { 0 })
                            )
                        }
                        "recv-window-conn" | "recv-window" if ty == "hysteria" => {
                            b[if k == "recv-window-conn" {
                                "connectionReceiveWindow"
                            } else {
                                "streamReceiveWindow"
                            }] = json!(int(&text(v)).unwrap_or(0))
                        }
                        "disable-mtu-discovery" if ty == "hysteria" => {
                            b["disableMtuDiscovery"] = json!(is_true(v) || text(v) == "1")
                        }
                        "ip" if ty == "tuic" => ip = text(v),
                        "token" if ty == "tuic" => {
                            b["protocolVersion"] = json!(4);
                            b["token"] = scalar(v);
                        }
                        "uuid" if ty == "tuic" => b["uuid"] = scalar(v),
                        "disable-sni" if ty == "tuic" => b["disableSNI"] = json!(is_true(v)),
                        "reduce-rtt" if ty == "tuic" => b["reduceRTT"] = json!(is_true(v)),
                        "congestion-controller" if ty == "tuic" => {
                            b["congestionController"] = scalar(v)
                        }
                        "udp-relay-mode" if ty == "tuic" => b["udpRelayMode"] = scalar(v),
                        _ => {}
                    }
                }
                if not_blank(&hop) {
                    b["serverPorts"] = json!(hop);
                }
                if not_blank(&ip) {
                    b["serverAddress"] = json!(ip);
                    if blank(&b["sni"]) && !super::is_ip(&ip) {
                        b["sni"] = json!(ip);
                    }
                }
            }
            _ => unreachable!(),
        }
        if matches!(ty, "http" | "vmess" | "vless" | "trojan") {
            if b["security"] == "tls"
                && blank(&b["sni"])
                && !blank(&b["host"])
                && !super::is_ip(&text(&b["host"]))
            {
                b["sni"] = b["host"].clone();
            }
            if !blank(&b["realityPubKey"]) && blank(&b["utlsFingerprint"]) {
                b["utlsFingerprint"] = json!(if not_blank(&global) {
                    &global
                } else {
                    "chrome"
                });
            }
        }
        result.push(Node::new(kind, b, true));
    }
    Ok(result)
}
