use super::{Rule, Settings};
use crate::config::not_blank;
use crate::config::protocols::{int, list};
use serde_json::{Value, json};

pub(super) fn normalize(s: &str) -> String {
    if let Some(v) = s.strip_prefix("geosite:") {
        return format!("geosite-{}", v.to_lowercase());
    }
    if let Some(v) = s.strip_prefix("geoip:") {
        return format!("geoip-{}", v.to_lowercase());
    }
    if s.starts_with("http://") || s.starts_with("https://") {
        let base = s
            .rsplit('/')
            .next()
            .unwrap_or("")
            .split('?')
            .next()
            .unwrap_or("");
        return if base.starts_with("geosite-")
            || base.starts_with("geoip-")
            || matches!(base, "geoip" | "geosite")
        {
            format!("user-{base}")
        } else {
            base.to_string()
        };
    }
    s.to_lowercase()
}
fn add(obj: &mut Value, key: &str, value: String) {
    if not_blank(&value) {
        if obj.get(key).is_none() {
            obj[key] = json!([]);
        }
        obj[key].as_array_mut().unwrap().push(json!(value));
    }
}
pub(super) fn match_rule(obj: &mut Value, values: &[&str], ip: bool) {
    // Legacy IP application replaces rule_set, including previously added domain sets.
    obj.as_object_mut().unwrap().remove("rule_set");
    for &v in values {
        if ip {
            if matches!(v, "geoip:private" | "geoip-private") {
                obj["ip_is_private"] = json!(true);
            } else if v.starts_with("geoip:")
                || v.starts_with("geoip-")
                || v.starts_with("http://")
                || v.starts_with("https://")
            {
                add(obj, "rule_set", normalize(v));
            } else {
                add(obj, "ip_cidr", v.into());
            }
        } else if v.starts_with("geosite:")
            || v.starts_with("geosite-")
            || v.starts_with("http://")
            || v.starts_with("https://")
        {
            add(obj, "rule_set", normalize(v));
        } else {
            let (key, text) = if let Some(v) = v.strip_prefix("full:") {
                ("domain", v)
            } else if let Some(v) = v.strip_prefix("domain:") {
                ("domain_suffix", v)
            } else if let Some(v) = v.strip_prefix("regexp:") {
                ("domain_regex", v)
            } else if let Some(v) = v.strip_prefix("keyword:") {
                ("domain_keyword", v)
            } else {
                ("domain_suffix", v)
            };
            add(obj, key, text.to_lowercase());
        }
    }
}
fn sets(values: &[&str], target: &mut Vec<Value>) {
    for &v in values {
        let url = if v.starts_with("geoip:") || v.starts_with("geoip-") {
            if normalize(v) == "geoip-private" {
                continue;
            }
            format!(
                "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/{}.srs",
                normalize(v)
            )
        } else if v.starts_with("geosite:") || v.starts_with("geosite-") {
            format!(
                "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/{}.srs",
                normalize(v)
            )
        } else if v.starts_with("http://") || v.starts_with("https://") {
            v.into()
        } else {
            continue;
        };
        let remote = v.starts_with("http://") || v.starts_with("https://");
        let mut set = json!({"type":"remote","tag":normalize(v),"format":if !remote || v.contains(".srs") {"binary"} else {"source"},"url":url,"http_client":"default-http-client"});
        if !remote {
            set["update_interval"] = json!("24h");
        }
        target.push(set);
    }
}
pub(super) fn dns_empty(obj: &Value) -> bool {
    let fields = [
        "rule_set",
        "domain",
        "domain_suffix",
        "domain_regex",
        "domain_keyword",
        "user_id",
        "ip_cidr",
    ];
    fields.iter().all(|k| {
        obj.get(*k)
            .and_then(Value::as_array)
            .is_none_or(Vec::is_empty)
    }) && obj.get("ip_is_private") != Some(&json!(true))
        && obj.get("match_response").is_none()
}
fn route_empty(obj: &Value, custom: &Value) -> bool {
    dns_empty(obj)
        && obj.get("source_ip_is_private") != Some(&json!(true))
        && obj.get("rule_set_ip_cidr_match_source") != Some(&json!(true))
        && ["port", "port_range", "source_ip_cidr"].iter().all(|k| {
            obj.get(*k)
                .and_then(Value::as_array)
                .is_none_or(Vec::is_empty)
        })
        && custom.is_null()
}
fn ports(obj: &mut Value, raw: &str, key: &str, range: &str) {
    if !not_blank(raw) {
        return;
    }
    obj[key] = json!([]);
    obj[range] = json!([]);
    for part in list(raw) {
        if part.contains(':') {
            obj[range].as_array_mut().unwrap().push(json!(part));
        } else if let Some(n) = int(part) {
            obj[key].as_array_mut().unwrap().push(json!(n));
        }
    }
}
/// Pure rule lowering. DNS response-IP evaluate/match_response is preserved.
pub(super) fn build(
    rule: &Rule,
    tag: &str,
    settings: &Settings,
    fake: bool,
) -> (Option<Value>, Vec<Value>, Vec<Value>, bool) {
    let mut out = json!({});
    let mut rule_sets = vec![];
    let mut dns = vec![];
    let uids: Vec<_> = rule.uids.iter().copied().filter(|v| *v >= 1000).collect();
    if !uids.is_empty() {
        out["user_id"] = json!(uids);
    }
    let domains = list(&rule.domains);
    let ips = list(&rule.ip);
    if not_blank(&rule.domains) {
        match_rule(&mut out, &domains, false);
        sets(&domains, &mut rule_sets);
    }
    if not_blank(&rule.ip) {
        match_rule(&mut out, &ips, true);
        sets(&ips, &mut rule_sets);
    }
    ports(&mut out, &rule.port, "port", "port_range");
    ports(
        &mut out,
        &rule.source_port,
        "source_port",
        "source_port_range",
    );
    if not_blank(&rule.network) {
        out["network"] = json!([rule.network]);
    }
    for item in list(&rule.source) {
        if matches!(item, "geoip:private" | "geoip-private") {
            out["source_ip_is_private"] = json!(true);
        } else if item.starts_with("geoip:") || item.starts_with("geoip-") {
            let tag = normalize(item);
            sets(&[&tag], &mut rule_sets);
            add(&mut out, "rule_set", tag);
            out["rule_set_ip_cidr_match_source"] = json!(true);
        } else {
            add(&mut out, "source_ip_cidr", item.into());
        }
    }
    if not_blank(&rule.protocol) {
        out["protocol"] = json!(list(&rule.protocol));
    }
    let mut query = json!({});
    if !uids.is_empty() {
        query["user_id"] = json!(uids);
    }
    if not_blank(&rule.domains) {
        match_rule(&mut query, &domains, false);
        sets(&domains, &mut rule_sets);
    }
    if matches!(rule.outbound, -2..=0) {
        let server = match rule.outbound {
            -1 => Some("dns-direct"),
            0 => Some("dns-remote"),
            _ => None,
        };
        if not_blank(&rule.ip) {
            let mut evaluate = query.clone();
            evaluate["action"] = json!("evaluate");
            evaluate["server"] = json!("dns-remote");
            dns.push(evaluate);
            let mut response = json!({"match_response":true});
            match_rule(&mut response, &ips, true);
            sets(&ips, &mut rule_sets);
            let mut matched = if dns_empty(&query) {
                response
            } else {
                json!({"type":"logical","mode":"and","rules":[query,response]})
            };
            matched["action"] = json!(if server.is_some() { "route" } else { "reject" });
            if let Some(s) = server {
                matched["server"] = json!(s);
            }
            dns.push(matched);
        } else {
            if rule.outbound == 0 && fake {
                let mut q = query.clone();
                q["server"] = json!("dns-fake");
                q["inbound"] = json!(["tun-in"]);
                dns.push(q);
            }
            if let Some(s) = server {
                query["server"] = json!(s);
            } else {
                query["action"] = json!("reject");
            }
            dns.push(query);
        }
    }
    if !settings.enable_dns_routing {
        dns.clear();
    }
    dns.retain(|v| {
        !dns_empty(v)
            || v.get("type") == Some(&json!("logical"))
            || v.get("action") == Some(&json!("evaluate"))
    });
    if route_empty(&out, &rule.custom) {
        return (None, dns, vec![], false);
    }
    if tag.is_empty() {
        return (None, dns, vec![], true);
    }
    if tag == "block" {
        out["action"] = json!("reject");
    } else {
        out["outbound"] = json!(tag);
    }
    super::merge(&mut out, &rule.custom);
    (Some(out), dns, rule_sets, false)
}
