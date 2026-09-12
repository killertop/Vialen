use super::{Rule, Settings};
use crate::config::protocols::{int, list};
use serde::Deserialize;
use serde_json::{Value, json};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct RuleSet {
    name: String,
    source: String,
    format: String,
    #[serde(rename = "match")]
    direction: String,
    #[serde(default)]
    initial_path: Option<String>,
}

pub(super) struct BuiltRule {
    pub route: Option<Value>,
    pub dns: Vec<Value>,
    pub sets: Vec<Value>,
    pub missing_outbound: bool,
    pub dns_safe: bool,
}

fn add(obj: &mut Value, key: &str, value: Value) {
    obj.as_object_mut()
        .unwrap()
        .entry(key)
        .or_insert(json!([]))
        .as_array_mut()
        .unwrap()
        .push(value);
}

// Native address input only; sets have a separate typed field.
pub(super) fn match_rule(obj: &mut Value, values: &[&str], ip: bool) {
    for &v in values {
        if ip {
            add(obj, "ip_cidr", json!(v));
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
            add(
                obj,
                key,
                json!(if key == "domain_regex" {
                    text.to_owned()
                } else {
                    text.to_lowercase()
                }),
            );
        }
    }
}

fn ports(obj: &mut Value, raw: &str, key: &str, range: &str) -> Result<(), String> {
    for part in list(raw) {
        if let Some((start, end)) = part.split_once(':') {
            let parse = |s: &str, default| {
                if s.is_empty() {
                    Some(default)
                } else {
                    s.parse::<u16>().ok()
                }
            };
            let (Some(a), Some(b)) = (parse(start, 0), parse(end, 65535)) else {
                return Err(format!("invalid {range}: {part}"));
            };
            if a > b {
                return Err(format!("reversed {range}: {part}"));
            }
            add(obj, range, json!(format!("{a}:{b}")));
        } else {
            let n = int(part)
                .filter(|v| (0..=65535).contains(v))
                .ok_or_else(|| format!("invalid {key}: {part}"))?;
            add(obj, key, json!(n));
        }
    }
    Ok(())
}

fn cidrs(raw: &str) -> Result<(), String> {
    for part in list(raw) {
        let (addr, prefix) = part
            .split_once('/')
            .map_or((part, None), |(a, p)| (a, Some(p)));
        let address = addr
            .parse::<std::net::IpAddr>()
            .map_err(|_| format!("invalid IP/CIDR: {part}; use the rule-set selector for sets"))?;
        if let Some(prefix) = prefix {
            let n = prefix
                .parse::<u8>()
                .map_err(|_| format!("invalid CIDR: {part}"))?;
            if n > if address.is_ipv4() { 32 } else { 128 } {
                return Err(format!("invalid CIDR: {part}"));
            }
        }
    }
    Ok(())
}

fn take_fields(obj: &mut Value, keys: &[&str]) -> Value {
    let mut part = json!({});
    for key in keys {
        if let Some(v) = obj.as_object_mut().unwrap().remove(*key) {
            part[*key] = v;
        }
    }
    part
}
fn combine(mode: &str, mut parts: Vec<Value>) -> Value {
    parts.retain(|p| p.as_object().is_some_and(|o| !o.is_empty()));
    if parts.len() == 1 {
        parts.remove(0)
    } else if parts.is_empty() {
        json!({})
    } else {
        json!({"type":"logical", "mode":mode, "rules":parts})
    }
}

pub(super) fn build(
    rule: &Rule,
    tag: &str,
    settings: &Settings,
    fake: bool,
) -> Result<BuiltRule, String> {
    build_inner(rule, tag, settings, fake).map_err(|e| format!("Rule {}: {e}", rule.id))
}
fn build_inner(
    rule: &Rule,
    tag: &str,
    settings: &Settings,
    fake: bool,
) -> Result<BuiltRule, String> {
    let mut out = json!({});
    for value in list(&rule.domains) {
        if value.starts_with("geoip:")
            || value.starts_with("geosite:")
            || value.starts_with("geoip-")
            || value.starts_with("geosite-")
            || value.contains("://")
        {
            return Err(
                "use native rule-set references, not database shorthand or URLs in domains".into(),
            );
        }
    }
    cidrs(&rule.ip)?;
    cidrs(&rule.source)?;
    match_rule(&mut out, &list(&rule.domains), false);
    match_rule(&mut out, &list(&rule.ip), true);
    if rule.ip_is_private {
        out["ip_is_private"] = json!(true);
    }
    if rule.source_ip_is_private {
        out["source_ip_is_private"] = json!(true);
    }
    for value in list(&rule.source) {
        add(&mut out, "source_ip_cidr", json!(value));
    }
    ports(&mut out, &rule.port, "port", "port_range")?;
    ports(
        &mut out,
        &rule.source_port,
        "source_port",
        "source_port_range",
    )?;
    for value in list(&rule.network) {
        if !matches!(value, "tcp" | "udp" | "icmp") {
            return Err(format!("invalid network: {value}"));
        }
        add(&mut out, "network", json!(value));
    }
    for value in list(&rule.protocol) {
        add(&mut out, "protocol", json!(value));
    }
    let uids: Vec<_> = rule.uids.iter().copied().filter(|v| *v >= 1000).collect();
    if rule.package_count > 0 && uids.is_empty() {
        return Err("no selected application could be resolved; refusing to widen the rule".into());
    }
    if !uids.is_empty() {
        out["user_id"] = json!(uids);
    }
    if rule.custom.get("type") == Some(&json!("logical"))
        && (!out.as_object().unwrap().is_empty() || !rule.rule_sets.is_empty())
    {
        return Err("custom logical rules must not be mixed with form conditions; put all conditions inside the custom rule".into());
    }
    // Preserve replacement / append / prepend semantics before changing structure.
    super::merge(&mut out, &rule.custom);
    if !rule.rule_sets.is_empty()
        && (out.get("rule_set").is_some() || out.get("rule_set_ip_cidr_match_source").is_some())
    {
        return Err("custom rule_set fields conflict with the form's typed references".into());
    }
    let mut sets = vec![];
    let mut destination = vec![];
    let mut source = vec![];
    let mut general = vec![];
    for set in &rule.rule_sets {
        if set.name.trim().is_empty() || !matches!(set.format.as_str(), "binary" | "source") {
            return Err("invalid rule-set name or format".into());
        }
        let remote = set.source.starts_with("https://");
        if (!remote && !set.source.starts_with('/'))
            || set.source.split('?').next().unwrap().ends_with(".db")
        {
            return Err(
                "rule-set source must be an HTTPS URL or imported local path, not a .db file"
                    .into(),
            );
        }
        // Full identity avoids basename collisions without a hashing dependency.
        let id = format!("rs:{}:{}", set.format, set.source);
        let mut declared =
            json!({"tag":id,"type":if remote {"remote"} else {"local"},"format":set.format});
        declared[if remote { "url" } else { "path" }] = json!(set.source);
        if remote {
            if let Some(path) = &set.initial_path {
                if !path.starts_with('/') { return Err("rule-set initial path must be absolute".into()); }
                declared["initial_path"] = json!(path);
            }
            declared["http_client"] = json!("default-http-client");
            declared["update_interval"] = json!("24h");
        }
        sets.push(declared);
        let mut predicate = json!({"rule_set":[id]});
        match set.direction.as_str() {
            "destination" => destination.push(predicate),
            "source" => {
                predicate["rule_set_ip_cidr_match_source"] = json!(true);
                source.push(predicate);
            }
            "rule" => general.push(predicate),
            _ => return Err("invalid rule-set match direction".into()),
        }
    }
    let has_conditions = !out.as_object().unwrap().is_empty() || !sets.is_empty();
    let mut built = BuiltRule {
        route: None,
        dns: vec![],
        sets,
        missing_outbound: false,
        dns_safe: true,
    };
    if !has_conditions {
        return Ok(built);
    }
    if tag.is_empty() {
        built.missing_outbound = true;
        built.sets.clear();
        return Ok(built);
    }
    // DNS cannot reproduce future connection attributes or arbitrary set contents.
    // The caller stops projection after the first non-projectable rule.
    built.dns_safe = rule.custom.is_null()
        && rule.rule_sets.is_empty()
        && !out.as_object().unwrap().is_empty()
        && out.as_object().unwrap().keys().all(|k| {
            matches!(
                k.as_str(),
                "domain" | "domain_suffix" | "domain_regex" | "domain_keyword"
            )
        });
    if built.dns_safe && settings.enable_dns_routing && matches!(rule.outbound, -2..=0) {
        let mut query = out.clone();
        if rule.outbound == -2 {
            query["action"] = json!("reject");
        } else {
            if rule.outbound == 0 && fake {
                let mut q = query.clone();
                q["server"] = json!("dns-fake");
                q["inbound"] = json!(["tun-in"]);
                built.dns.push(q);
            }
            query["server"] = json!(if rule.outbound == -1 {
                "dns-direct"
            } else {
                "dns-remote"
            });
        }
        built.dns.push(query);
    } else if !matches!(rule.outbound, -2..=0) {
        built.dns_safe = false;
    }
    let mut actions = take_fields(
        &mut out,
        &["action", "outbound", "invert", "method", "no_drop"],
    );
    if actions.get("action").is_none() {
        actions["action"] = json!(if tag == "block" { "reject" } else { "route" });
    }
    if tag != "block" && actions.get("outbound").is_none() && actions["action"] == "route" {
        actions["outbound"] = json!(tag);
    }
    let mut parts = vec![];
    if !destination.is_empty() {
        destination.push(take_fields(
            &mut out,
            &[
                "domain",
                "domain_suffix",
                "domain_regex",
                "domain_keyword",
                "ip_cidr",
                "ip_is_private",
            ],
        ));
        parts.push(combine("or", destination));
    }
    if !source.is_empty() {
        source.push(take_fields(
            &mut out,
            &["source_ip_cidr", "source_ip_is_private"],
        ));
        parts.push(combine("or", source));
    }
    if !general.is_empty() {
        parts.push(combine("or", general));
    }
    parts.push(out);
    let mut route = combine("and", parts);
    super::merge(&mut route, &actions);
    built.route = Some(route);
    Ok(built)
}
