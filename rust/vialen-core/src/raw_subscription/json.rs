use super::clash::text;
use super::{Node, Result};
use crate::config::not_blank;
use serde_json::{Value, json};
fn string(v: &Value) -> Value {
    v.as_str()
        .filter(|s| not_blank(s))
        .map_or(Value::Null, |s| json!(s))
}
fn number(v: &Value) -> Value {
    if let Some(n) = v.as_i64() {
        return json!(n as i32);
    }
    let n = v.as_f64().or_else(|| {
        v.as_str()
            .and_then(|s| s.trim_matches(|c: char| c <= ' ').parse::<f64>().ok())
    });
    n.map_or(Value::Null, |n| json!(n as i32))
}
fn boolean(v: &Value) -> Value {
    v.as_bool()
        .or_else(|| {
            v.as_str().and_then(|s| match s.to_lowercase().as_str() {
                "true" => Some(true),
                "false" => Some(false),
                _ => None,
            })
        })
        .map_or(Value::Null, |b| json!(b))
}
fn opt(v: &Value) -> String {
    if v.is_null() { String::new() } else { text(v) }
}
pub(super) fn parse(v: &Value) -> Result<Vec<Node>> {
    let mut out = vec![];
    if let Some(o) = v.as_object() {
        if o.contains_key("server") && (o.contains_key("up") || o.contains_key("up_mbps")) {
            let server = opt(&v["server"]);
            let (host, ports) = server.rsplit_once(':').unwrap_or((&server, &server));
            let mut b = json!({"protocolVersion":1,"serverAddress":host,"serverPorts":ports,"uploadMbps":number(&v["up_mbps"]),"downloadMbps":number(&v["down_mbps"]),"obfuscation":string(&v["obfs"]),"sni":string(&v["server_name"]),"alpn":string(&v["alpn"]),"allowInsecure":boolean(&v["insecure"]),"streamReceiveWindow":number(&v["recv_window_conn"]),"connectionReceiveWindow":number(&v["recv_window"]),"disableMtuDiscovery":boolean(&v["disable_mtu_discovery"])});
            for (key, kind) in [("auth", 2), ("auth_str", 1)] {
                if !string(&v[key]).is_null() {
                    b["authPayloadType"] = json!(kind);
                    b["authPayload"] = v[key].clone();
                }
            }
            if let Some(protocol) = string(&v["protocol"]).as_str() {
                if protocol.to_lowercase() != "udp" {
                    return Err("UNSUPPORTED_HYSTERIA_PROTOCOL");
                }
                b["protocol"] = json!(0);
            }
            out.push(Node::new("Hysteria", b, false));
        } else if o.contains_key("method") {
            let mut b = json!({"serverAddress":string(&v["server"]),"serverPort":number(&v["server_port"]),"password":string(&v["password"]),"method":string(&v["method"]),"name":opt(&v["remarks"])});
            if !string(&v["plugin"]).is_null() {
                b["plugin"] = json!(format!("{};{}", text(&v["plugin"]), opt(&v["plugin_opts"])));
            }
            out.push(Node::new("Shadowsocks", b, false));
        } else if o.contains_key("outbounds") {
            let list = v["outbounds"].as_array().ok_or("INVALID_JSON_OUTBOUNDS")?;
            for item in list {
                if let Some(ty) = item["type"].as_str().filter(|s| not_blank(s))
                    && !matches!(ty, "dns" | "block" | "direct" | "selector" | "urltest")
                {
                    out.push(Node::new(
                        "Config",
                        json!({"type":1,"config":item.to_string(),"name":string(&item["tag"])}),
                        false,
                    ));
                }
            }
        } else if o.contains_key("server") && o.contains_key("server_port") {
            out.push(Node::new(
                "Config",
                json!({"type":1,"config":v.to_string()}),
                false,
            ));
        }
    } else if let Some(a) = v.as_array() {
        for item in a {
            if item.is_object() || item.is_array() {
                out.extend(parse(item)?);
            } else if let Some(s) = item.as_str() {
                if super::tokener::parse(s).is_ok_and(|v| v.is_object() || v.is_array()) {
                    return Err("INVALID_NESTED_JSON_STRING");
                }
            } else {
                // Legacy isJsonObjectValid casts non-container values to String;
                // a number, boolean or JSON null aborts the entire JSON branch.
                return Err("INVALID_JSON_ARRAY_ITEM");
            }
        }
        // Legacy applies defaults only after processing array children.
        for n in &mut out {
            n.initialize = true;
        }
    } else {
        return Err("INVALID_JSON_ROOT");
    }
    Ok(out)
}
