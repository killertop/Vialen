//! Complete config generation from a frozen platform/database snapshot.
//! Kotlin performs IO and applies returned metadata; all config policy stays here.
mod dns;
pub(crate) use dns::is_ip;
mod rules;
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

#[derive(Deserialize)]
#[serde(tag = "mode")]
enum Input {
    #[serde(rename = "snapshot")]
    Snapshot(Box<Request>),
    #[serde(rename = "passthrough")]
    Passthrough(FullCustom),
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct FullCustom {
    version: u32,
    selected: i64,
    full_config: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    version: u32,
    selected: i64,
    for_test: bool,
    for_export: bool,
    settings: Settings,
    profiles: Vec<Node>,
    groups: Vec<Group>,
    rules: Vec<Rule>,
    selector_ids: Vec<i64>,
    selector_order: Vec<i64>,
    extra_ids: Vec<i64>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Settings {
    service_mode: String,
    allow_access: bool,
    remote_dns: String,
    direct_dns: String,
    enable_dns_routing: bool,
    fake_dns: bool,
    sniffing: i32,
    ipv6: i32,
    log_level: i32,
    tun: i32,
    mtu: i32,
    mixed_port: i32,
    resolve_destination: bool,
    bypass_lan: bool,
    global_insecure: bool,
    server_strategy: String,
    custom: Value,
    tun_v4: String,
    tun_v6: String,
}
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct Node {
    id: i64,
    group_id: i64,
    #[serde(rename = "name")]
    _name: String,
    server: String,
    outbound: Option<Value>,
    chain: Option<Vec<i64>>,
    full_config: Option<String>,
    custom_outbound: Value,
    custom_config: Value,
    mux: Option<Mux>,
    uot: bool,
}
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct Mux {
    enabled: bool,
    padding: bool,
    concurrency: i32,
    kind: i32,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Group {
    id: i64,
    selector: bool,
    front: i64,
    landing: i64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Rule {
    id: i64,
    domains: String,
    ip: String,
    port: String,
    source_port: String,
    network: String,
    source: String,
    protocol: String,
    outbound: i64,
    uids: Vec<i32>,
    package_count: usize,
    custom: Value,
    #[serde(default)]
    rule_sets: Vec<rules::RuleSet>,
    #[serde(default)]
    ip_is_private: bool,
    #[serde(default)]
    source_ip_is_private: bool,
}

/// Existing deep map merge, including +array prepend and array+ append semantics.
fn merge(dst: &mut Value, src: &Value) {
    let Some(source) = src.as_object() else {
        return;
    };
    let target = dst.as_object_mut().expect("config object");
    for (key, value) in source {
        if value.is_object() && target.get(key).is_some_and(Value::is_object) {
            merge(target.get_mut(key).unwrap(), value);
        } else if let Some(items) = value.as_array() {
            if let Some(k) = key.strip_prefix('+') {
                let mut v = items.clone();
                v.extend(
                    target
                        .get(k)
                        .and_then(Value::as_array)
                        .cloned()
                        .unwrap_or_default(),
                );
                target.insert(k.into(), json!(v));
            } else if let Some(k) = key.strip_suffix('+') {
                let mut v = target
                    .get(k)
                    .and_then(Value::as_array)
                    .cloned()
                    .unwrap_or_default();
                v.extend(items.clone());
                target.insert(k.into(), json!(v));
            } else {
                target.insert(key.clone(), value.clone());
            }
        } else {
            target.insert(key.clone(), value.clone());
        }
    }
}
fn strategy(ipv6: i32) -> &'static str {
    match ipv6 {
        0 => "ipv4_only",
        2 => "prefer_ipv6",
        3 => "ipv6_only",
        _ => "prefer_ipv4",
    }
}
struct Generated {
    id: i64,
    value: Value,
    custom: Value,
    endpoint: bool,
}
struct Builder<'a> {
    req: &'a Request,
    nodes: HashMap<i64, &'a Node>,
    groups: HashMap<i64, &'a Group>,
    generated: Vec<Generated>,
    global: HashMap<i64, String>,
    chains: HashMap<i64, String>,
    tags: Vec<(i64, String)>,
    traffic: serde_json::Map<String, Value>,
    direct_domains: Vec<String>,
    bypass_nodes: HashSet<i64>,
    domain_strategy: String,
}
impl Builder<'_> {
    fn internal_chain(&self, id: i64, path: &mut HashSet<i64>) -> Result<Vec<i64>, &'static str> {
        let Some(node) = self.nodes.get(&id) else {
            return Ok(vec![]);
        };
        if !path.insert(id) || path.len() > 512 {
            return Err("CYCLIC_OR_EXCESSIVE_CHAIN");
        }
        let result = if let Some(chain) = &node.chain {
            let mut result = vec![];
            for child in chain {
                result.extend(self.internal_chain(*child, path)?);
            }
            result.reverse();
            result
        } else {
            vec![id]
        };
        path.remove(&id);
        Ok(result)
    }
    fn chain(&mut self, chain_id: i64, id: i64) -> Result<String, &'static str> {
        if let Some(tag) = self.chains.get(&chain_id) {
            return Ok(tag.clone());
        }
        let node = (*self.nodes.get(&id).ok_or("MISSING_PROFILE")?).clone();
        let mut profile_ids = self.internal_chain(id, &mut HashSet::new())?;
        if let Some(group) = self.groups.get(&node.group_id) {
            if self.nodes.contains_key(&group.front) {
                profile_ids.push(group.front);
            }
            if self.nodes.contains_key(&group.landing) {
                profile_ids.insert(0, group.landing);
            }
        }
        if profile_ids.is_empty() {
            return Err("EMPTY_CHAIN");
        }
        let mut traffic = profile_ids.clone();
        if !traffic.contains(&id) {
            traffic.push(id);
        }
        let mut previous: Option<usize> = None;
        let mut mux_applied = false;
        let mut chain_tag = String::new();
        for (index, node_id) in profile_ids.iter().enumerate() {
            let item = self.nodes[node_id];
            let is_global = index + 1 == profile_ids.len();
            // Names are presentation only. The hop index distinguishes repeated
            // nodes within one chain; cached chains share one generated graph.
            let mut tag = if is_global {
                format!("g-{node_id}")
            } else {
                format!("c-{chain_id}-{index}-{node_id}")
            };
            if is_global {
                self.bypass_nodes.insert(*node_id);
            }
            if chain_id == 0 && index == 0 {
                tag = "proxy".into();
            }
            if let Some(prev) = previous {
                self.generated[prev].value["detour"] = json!(tag);
            } else {
                chain_tag = tag.clone();
            }
            if is_global {
                if let Some(existing) = self.global.get(node_id) {
                    // The global node may have been emitted as "proxy",
                    // so the provisional g-id may not exist.
                    if let Some(prev) = previous {
                        self.generated[prev].value["detour"] = json!(existing);
                    } else {
                        chain_tag = existing.clone();
                    }
                    continue;
                }
                self.global.insert(*node_id, tag.clone());
            }
            let profile = item.outbound.as_ref().ok_or("UNRESOLVED_CHAIN_OUTBOUND")?;
            let mut outbound =
                crate::config::generate_value(profile.clone(), self.req.settings.global_insecure)?;
            if !mux_applied
                && let Some(mux) = &item.mux
                && mux.enabled
            {
                mux_applied = true;
                outbound["multiplex"] = json!({"enabled":true,"padding":mux.padding,"max_streams":mux.concurrency,
                        "protocol":match mux.kind {1=>"smux",2=>"yamux",_=>"h2mux"}});
            }
            if item.uot {
                outbound["udp_over_tcp"] = json!(true);
            }
            if let Some(prev) = previous {
                let prev_node = self.nodes[&self.generated[prev].id];
                if !self.domain_strategy.is_empty() && !dns::is_ip(&prev_node.server) {
                    self.direct_domains
                        .push(format!("full:{}", prev_node.server));
                }
            }
            outbound["domain_strategy"] = json!(if self.req.for_test {
                ""
            } else {
                &self.domain_strategy
            });
            outbound["tag"] = json!(tag);
            previous = Some(self.generated.len());
            self.generated.push(Generated {
                id: *node_id,
                value: outbound,
                custom: item.custom_outbound.clone(),
                endpoint: profile.get("kind") == Some(&json!("WireGuard")),
            });
        }
        self.chains.insert(chain_id, chain_tag.clone());
        self.traffic.insert(chain_tag.clone(), json!(traffic));
        Ok(chain_tag)
    }
}

fn build(req: Request) -> Result<Value, String> {
    if req.version != 1 {
        return Err("UNSUPPORTED_VERSION".into());
    }
    let nodes: HashMap<_, _> = req.profiles.iter().map(|v| (v.id, v)).collect();
    let groups: HashMap<_, _> = req.groups.iter().map(|v| (v.id, v)).collect();
    if nodes.len() != req.profiles.len() || groups.len() != req.groups.len() {
        return Err("DUPLICATE_SNAPSHOT_ID".into());
    }
    let selected = *nodes.get(&req.selected).ok_or("MISSING_SELECTED_PROFILE")?;
    if let Some(config) = &selected.full_config {
        return Ok(
            json!({"config":config,"traffic":{"proxy":[req.selected]},"tags":[[req.selected,"proxy"]],"selector_group":-1,"generated":[],"warnings":[]}),
        );
    }
    let group = groups.get(&selected.group_id);
    let selector = !req.for_test && !req.for_export && group.is_some_and(|g| g.selector);
    let s = &req.settings;
    let ipv6 = if req.for_test { 1 } else { s.ipv6 };
    let fake = s.fake_dns && !req.for_test;
    let domain_strategy = s.server_strategy.replace("auto", "prefer_ipv4");
    let mut b = Builder {
        req: &req,
        nodes,
        groups,
        generated: vec![],
        global: HashMap::new(),
        chains: HashMap::new(),
        tags: vec![],
        traffic: serde_json::Map::new(),
        direct_domains: vec![],
        bypass_nodes: HashSet::new(),
        domain_strategy: domain_strategy.clone(),
    };
    let mut outbounds = vec![];
    if selector {
        for id in &req.selector_ids {
            let tag = b.chain(*id, *id)?;
            b.tags.push((*id, tag));
        }
        let order: HashSet<_> = req.selector_order.iter().copied().collect();
        let ids: HashSet<_> = req.selector_ids.iter().copied().collect();
        if order != ids || req.selector_order.len() != order.len() {
            return Err("INVALID_SELECTOR_ORDER".into());
        }
        let options: Vec<_> = req
            .selector_order
            .iter()
            .map(|id| {
                b.tags
                    .iter()
                    .find(|(key, _)| key == id)
                    .map(|(_, tag)| tag.clone())
                    .ok_or("INVALID_SELECTOR_ORDER")
            })
            .collect::<Result<_, _>>()?;
        let mut out = json!({"type":"selector","tag":"proxy","outbounds":options});
        if let Some((_, tag)) = b.tags.iter().find(|(id, _)| *id == req.selected) {
            out["default"] = json!(tag);
        }
        outbounds.push(out);
    } else {
        b.chain(0, req.selected)?;
    }
    for id in &req.extra_ids {
        let tag = b.chain(*id, *id)?;
        if let Some(entry) = b.tags.iter_mut().find(|(key, _)| key == id) {
            entry.1 = tag;
        } else {
            b.tags.push((*id, tag));
        }
    }
    let mut endpoints = vec![];
    let mut generated_ids = vec![];
    for item in &b.generated {
        let mut out = item.value.clone();
        merge(&mut out, &item.custom);
        if item.endpoint {
            endpoints.push(out);
        } else {
            outbounds.push(out);
        }
        generated_ids.push(item.id);
    }
    let mut route_rules = vec![];
    let mut dns_rules = vec![];
    let mut rule_sets = vec![];
    let mut warnings = vec![];
    let mut dns_projection_open = true;
    if !req.for_test {
        for rule in &req.rules {
            if s.service_mode != "vpn" {
                for _ in 0..rule.package_count {
                    warnings.push(json!([rule.id, "PACKAGE_REQUIRES_VPN"]));
                }
            }
            let tag = match rule.outbound {
                0 => "proxy",
                -1 => "bypass",
                -2 => "block",
                id if id == req.selected => "proxy",
                id => b
                    .tags
                    .iter()
                    .find(|(key, _)| *key == id)
                    .map_or("", |(_, tag)| tag.as_str()),
            };
            let mut lowered = rules::build(rule, tag, s, fake)?;
            if lowered.route.is_some() && !lowered.dns_safe { dns_projection_open = false; }
            if !dns_projection_open {
                lowered.dns.clear();
                if s.enable_dns_routing && lowered.route.is_some() { warnings.push(json!([rule.id, "DNS_RULE_NOT_PROJECTED"])); }
            }
            if let Some(route) = lowered.route {
                route_rules.push(route);
            } else if lowered.missing_outbound {
                warnings.push(json!([rule.id, "MISSING_RULE_OUTBOUND"]));
            }
            dns_rules.append(&mut lowered.dns);
            rule_sets.append(&mut lowered.sets);
        }
    }
    let mut seen = HashSet::new();
    rule_sets.retain(|v| seen.insert(v["tag"].as_str().unwrap_or("").to_string()));
    rule_sets.sort_by(|a, b| {
        a["tag"]
            .as_str()
            .unwrap_or("")
            .encode_utf16()
            .cmp(b["tag"].as_str().unwrap_or("").encode_utf16())
    });
    for tag in ["direct", "bypass"] {
        outbounds.push(json!({"type":"direct","tag":tag}));
    }
    for id in &b.bypass_nodes {
        let node = b.nodes[id];
        let mut server = node.server.clone();
        if let Some(profile) = &node.outbound
            && profile.get("kind") == Some(&json!("Custom"))
            && let Ok(v) = crate::config::generate_value(profile.clone(), s.global_insecure)
            && let Some(value) = v.get("server")
        {
            server = match value {
                Value::String(s) => s.clone(),
                _ => value.to_string(),
            };
        }
        if !dns::is_ip(&server) {
            b.direct_domains.push(format!("full:{server}"));
        }
    }
    let remote = dns::addresses(&s.remote_dns);
    let direct = dns::addresses(&s.direct_dns);
    for address in &remote {
        if let Some(host) = dns::remote_host(address)
            && !dns::is_ip(&host)
        {
            b.direct_domains.push(format!("full:{host}"));
        }
    }
    // Bootstrap DNS uses the core direct dialer (an empty direct outbound is
    // not a valid detour). Remote queries explicitly use the selected proxy;
    // their server hostname still resolves through dns-direct. Overlays follow.
    let mut servers = vec![
        dns::server("local", "dns-local", None, None),
        dns::server(
            direct.first().ok_or("NO_DIRECT_DNS")?,
            "dns-direct",
            Some("dns-local"),
            None,
        ),
    ];
    if !req.for_test {
        servers.push(dns::server(
            remote.first().ok_or("NO_REMOTE_DNS")?,
            "dns-remote",
            Some("dns-direct"),
            Some("proxy"),
        ));
    }
    if req.for_test {
        dns_rules.clear();
    } else {
        route_rules.insert(0, json!({"protocol":["dns"],"action":"hijack-dns"}));
        route_rules.insert(0, json!({"port":[53],"action":"hijack-dns"}));
        if s.sniffing > 0 {
            let mut r = json!({"action":"sniff"});
            if s.sniffing == 2 {
                r["override_destination"] = json!(true);
            }
            route_rules.insert(0, r);
        }
        if s.resolve_destination {
            route_rules.insert(0, json!({"action":"resolve","strategy":strategy(ipv6)}));
        }
        if s.bypass_lan {
            route_rules.push(json!({"outbound":"bypass","ip_is_private":true}));
        }
        route_rules.push(json!({"ip_cidr":["224.0.0.0/3","ff00::/8"],"source_ip_cidr":["224.0.0.0/3","ff00::/8"],"action":"reject"}));
        if fake {
            servers.push(dns::server("fakeip", "dns-fake", None, None));
            dns_rules.push(json!({"inbound":["tun-in"],"server":"dns-fake","disable_cache":true}));
        }
        dns_rules.insert(0, json!({"outbound":["any"],"server":"dns-direct"}));
        if !b.direct_domains.is_empty() {
            b.direct_domains.sort();
            b.direct_domains.dedup();
            let mut r = json!({"server":"dns-direct"});
            rules::match_rule(
                &mut r,
                &b.direct_domains
                    .iter()
                    .map(String::as_str)
                    .collect::<Vec<_>>(),
                false,
            );
            dns_rules.insert(0, r);
        }
    }
    let mut inbounds = vec![];
    if !req.for_test {
        if s.service_mode == "vpn" {
            let mut addresses = vec![];
            if ipv6 != 3 {
                addresses.push(format!("{}/28", s.tun_v4));
            }
            if ipv6 != 0 {
                addresses.push(format!("{}/126", s.tun_v6));
            }
            inbounds.push(json!({"type":"tun","tag":"tun-in","stack":match s.tun{0=>"gvisor",1=>"system",_=>"mixed"},"endpoint_independent_nat":true,"mtu":s.mtu,"address":addresses}));
        }
        inbounds.push(json!({"type":"mixed","tag":"mixed-in","listen":if s.allow_access{"0.0.0.0"}else{"127.0.0.1"},"listen_port":s.mixed_port}));
    }
    let mut dns_options = json!({"servers":servers,"rules":dns_rules,"final":if req.for_test{"dns-direct"}else{"dns-remote"}});
    if !domain_strategy.is_empty() {
        dns_options["strategy"] = json!(domain_strategy);
    } else if matches!(ipv6, 0..=3) {
        dns_options["strategy"] = json!(strategy(ipv6));
    }
    let mut route = json!({"final":"proxy","auto_detect_interface":true,"rules":route_rules,"rule_set":rule_sets});
    let mut config = json!({"log":{"level":match s.log_level{0=>"panic",1=>"warn",3=>"debug",4=>"trace",_=>"info"}},"dns":dns_options,"inbounds":inbounds,"outbounds":outbounds,"endpoints":endpoints});
    if !rule_sets.is_empty() {
        route["default_http_client"] = json!("default-http-client");
        config["http_clients"] = json!([{"tag":"default-http-client"}]);
    }
    config["route"] = route;
    if !req.for_test {
        merge(&mut config, &s.custom);
    }
    merge(&mut config, &selected.custom_config);
    Ok(
        json!({"config":config.to_string(),"traffic":b.traffic,"tags":b.tags,"selector_group":if selector{selected.group_id}else{-1},"generated":generated_ids,"warnings":warnings}),
    )
}
pub fn generate(input: &[u8]) -> Vec<u8> {
    let result = serde_json::from_slice::<Input>(input)
        .map_err(|_| "INVALID_CONFIG_SNAPSHOT".to_string())
        .and_then(|input| match input {
            Input::Snapshot(request) => build(*request),
            Input::Passthrough(full) => {
                if full.version != 1 { return Err("UNSUPPORTED_VERSION".into()); }
                Ok(json!({"config":full.full_config,"traffic":{"proxy":[full.selected]},"tags":[[full.selected,"proxy"]],"selector_group":-1,"generated":[],"warnings":[]}))
            }
        });
    let value = match result {
        Ok(mut v) => {
            v["version"] = json!(1);
            v["status"] = json!("SUCCESS");
            v
        }
        Err(e) => json!({"version":1,"status":"ERROR","error":e}),
    };
    serde_json::to_vec(&value).expect("serializable config")
}

#[cfg(test)]
mod tests {
    use super::*;
    fn request() -> Value {
        json!({"mode":"snapshot","version":1,"selected":1,"for_test":false,"for_export":false,
            "settings":{"service_mode":"vpn","allow_access":false,"remote_dns":"https://dns.example/dns-query","direct_dns":"1.1.1.1","enable_dns_routing":true,"fake_dns":false,"sniffing":0,"ipv6":1,"log_level":2,"tun":2,"mtu":9000,"mixed_port":2080,"resolve_destination":false,"bypass_lan":false,"global_insecure":false,"server_strategy":"","custom":null,"tun_v4":"172.19.0.1","tun_v6":"fdfe:dcba:9876::1"},
            "profiles":[{"id":1,"group_id":1,"name":"one","server":"example.com","outbound":{"kind":"Socks","server":"example.com","port":1080,"protocol":2,"username":"","password":""},"chain":null,"full_config":null,"custom_outbound":null,"custom_config":null,"mux":null,"uot":false}],
            "groups":[],"rules":[],"selector_ids":[],"selector_order":[],"extra_ids":[]})
    }
    fn run(v: &Value) -> Value {
        serde_json::from_slice(&generate(&serde_json::to_vec(v).unwrap())).unwrap()
    }
    fn rule() -> Value {
        json!({"id":1,"domains":"","ip":"","port":"","source_port":"","network":"","source":"","protocol":"","outbound":999,"uids":[],"package_count":0,"custom":null})
    }
    fn generated_rule(mut r: Value) -> Value {
        r["outbound"] = json!(-1);
        let mut v = request();
        v["rules"] = json!([r]);
        let output = run(&v);
        assert_eq!(output["status"], "SUCCESS", "{output}");
        serde_json::from_str(output["config"].as_str().unwrap()).unwrap()
    }
    fn rule_set(name: &str, direction: &str) -> Value {
        json!({"name":name,"source":format!("https://example.net/{name}.srs"),"format":"binary","match":direction})
    }
    #[test]
    fn manual_bootstrap_preserves_remote_auto_update() {
        let mut r = rule();
        let mut reference = rule_set("geoip-cn", "destination");
        reference["initial_path"] = json!("/data/user/0/com.vialen.app/files/remote-rule-sets/test.srs");
        r["rule_sets"] = json!([reference]);
        let c = generated_rule(r);
        let set = &c["route"]["rule_set"][0];
        assert_eq!(set["type"], "remote");
        assert_eq!(set["update_interval"], "24h");
        assert_eq!(set["url"], "https://example.net/geoip-cn.srs");
        assert_eq!(set["initial_path"], "/data/user/0/com.vialen.app/files/remote-rule-sets/test.srs");
    }
    #[test]
    fn combined_destination_references_survive() {
        for kind in 0..3 {
            let mut r = rule();
            r["rule_sets"] = json!([rule_set("geosite-cn", "destination")]);
            match kind {
                0 => r["rule_sets"].as_array_mut().unwrap().push(rule_set("geoip-cn", "destination")),
                1 => r["ip"] = json!("203.0.113.0/24"),
                _ => r["ip_is_private"] = json!(true),
            }
            let c = generated_rule(r);
            assert!(c["route"]["rules"].to_string().contains("geosite-cn"), "{c}");
        }
    }
    #[test]
    fn source_geo_is_isolated_from_destination() {
        let mut r = rule();
        r["rule_sets"] = json!([rule_set("geoip-cn", "source"),rule_set("geoip-us", "destination")]);
        let c = generated_rule(r);
        let route = c["route"]["rules"].as_array().unwrap().iter().find(|r| r["outbound"] == "bypass").unwrap();
        assert_eq!(route["type"], "logical");
        assert!(route.get("rule_set_ip_cidr_match_source").is_none());
    }
    #[test]
    fn connection_only_rules_are_not_empty() {
        for (key, value) in [("source_port", "1234"), ("network", "tcp"), ("protocol", "tls")] {
            let mut r = rule();
            r[key] = json!(value);
            let c = generated_rule(r);
            assert!(c["route"]["rules"].as_array().unwrap().iter().any(|r| r["outbound"] == "bypass"), "{key}: {c}");
        }
    }
    #[test]
    fn invalid_port_cannot_disappear() {
        let mut v = request();
        let mut r = rule();
        r["domains"] = json!("example.com");
        r["port"] = json!("not-a-port");
        r["outbound"] = json!(-2);
        v["rules"] = json!([r]);
        assert_eq!(run(&v)["status"], "ERROR");
    }
    #[test]
    fn connection_constraint_prevents_dns_reject_and_shadowing() {
        let mut v = request();
        let mut first = rule();
        first["domains"] = json!("example.com");
        first["port"] = json!("443");
        first["outbound"] = json!(-1);
        let mut second = rule();
        second["domains"] = json!("example.com");
        second["outbound"] = json!(-2);
        v["rules"] = json!([first, second]);
        let output = run(&v);
        let c: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
        assert!(!c["dns"]["rules"].to_string().contains("reject"), "{c}");
    }
    // Validate the generated graph itself, independently of the Kotlin oracle.
    fn assert_outbound_tag_integrity(config: &Value) {
        let mut tags = HashSet::new();
        for key in ["outbounds", "endpoints"] {
            if let Some(nodes) = config[key].as_array() {
                for node in nodes {
                    let tag = node["tag"].as_str().expect("generated node tag");
                    assert!(tags.insert(tag), "duplicate tag: {tag}");
                }
            }
        }
        fn visit(value: &Value, tags: &HashSet<&str>) {
            match value {
                Value::Object(object) => {
                    if let Some(Value::String(tag)) = object.get("detour") {
                        assert!(tags.contains(tag.as_str()), "dangling detour: {tag}");
                    }
                    if object.get("type").is_some_and(|v| v == "selector") {
                        for tag in object["outbounds"].as_array().unwrap() {
                            let tag = tag.as_str().unwrap();
                            assert!(tags.contains(tag), "dangling selector option: {tag}");
                        }
                        if let Some(Value::String(tag)) = object.get("default") {
                            assert!(
                                tags.contains(tag.as_str()),
                                "dangling selector default: {tag}"
                            );
                            assert!(
                                object["outbounds"]
                                    .as_array()
                                    .unwrap()
                                    .contains(&json!(tag))
                            );
                        }
                    }
                    for child in object.values() {
                        visit(child, tags);
                    }
                }
                Value::Array(values) => {
                    for child in values {
                        visit(child, tags);
                    }
                }
                _ => {}
            }
        }
        visit(config, &tags);
    }
    fn reused_global_request(selector: bool, reverse_order: bool) -> Value {
        let mut v = request();
        let mut second = v["profiles"][0].clone();
        second["id"] = json!(2);
        second["name"] = json!("two");
        let mut chain = v["profiles"][0].clone();
        chain["id"] = json!(3);
        chain["name"] = json!("chain");
        chain["chain"] = json!([1, 2]); // Builder traverses from node 2 to node 1.
        chain["outbound"] = Value::Null;
        v["profiles"]
            .as_array_mut()
            .unwrap()
            .extend([second, chain]);
        if selector {
            v["groups"] = json!([{"id":1,"selector":true,"front":0,"landing":0}]);
            v["selector_ids"] = if reverse_order {
                json!([3, 1])
            } else {
                json!([1, 3])
            };
            v["selector_order"] = json!([1, 3]);
        } else {
            v["extra_ids"] = json!([3]);
        }
        v
    }
    #[test]
    fn chain_reuses_selected_global_tag_without_dangling_detour() {
        let output = run(&reused_global_request(false, false));
        assert_eq!(output["status"], "SUCCESS");
        let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
        eprintln!("TAG_REUSE_CONFIG nonselector={config}");
        assert_outbound_tag_integrity(&config);
        let hop = config["outbounds"]
            .as_array()
            .unwrap()
            .iter()
            .find(|v| v["tag"] == "c-3-0-2")
            .unwrap();
        assert_eq!(hop["detour"], "proxy");
    }
    #[test]
    fn selector_chain_reuse_has_complete_tag_references_in_both_orders() {
        for reverse_order in [false, true] {
            let output = run(&reused_global_request(true, reverse_order));
            assert_eq!(output["status"], "SUCCESS");
            let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
            eprintln!("TAG_REUSE_CONFIG selector_reverse={reverse_order} config={config}");
            assert_outbound_tag_integrity(&config);
            let hop = config["outbounds"]
                .as_array()
                .unwrap()
                .iter()
                .find(|v| v["tag"] == "c-3-0-2")
                .unwrap();
            assert_eq!(hop["detour"], "g-1");
        }
    }
    #[test]
    fn selector_tags_ignore_names_and_reuse_chains_with_repeated_hops() {
        for name in ["direct", "bypass", "proxy", "g-1", "c-3-0-2", "renamed"] {
            let mut v = reused_global_request(true, false);
            for node in v["profiles"].as_array_mut().unwrap() {
                node["name"] = json!(name);
            }
            v["profiles"][2]["chain"] = json!([1, 2, 2]);
            v["extra_ids"] = json!([3, 3]);
            let output = run(&v);
            assert_eq!(output["status"], "SUCCESS", "{output}");
            let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
            assert_outbound_tag_integrity(&config);
            assert_eq!(output["tags"], json!([[1, "g-1"], [3, "c-3-0-2"]]));
            let outbounds = config["outbounds"].as_array().unwrap();
            assert_eq!(outbounds.iter().find(|o| o["tag"] == "c-3-0-2").unwrap()["detour"], "c-3-1-2");
            assert_eq!(outbounds.iter().find(|o| o["tag"] == "c-3-1-2").unwrap()["detour"], "g-1");
            assert_eq!(config["route"]["final"], "proxy");
        }
    }
    #[test]
    fn nested_chain_tags_are_unique_and_bootstrap_dns_stays_direct() {
        let mut v = reused_global_request(true, false);
        let mut nested = v["profiles"][2].clone();
        nested["id"] = json!(4);
        nested["chain"] = json!([3, 3]);
        v["profiles"].as_array_mut().unwrap().push(nested);
        v["selector_ids"] = json!([1, 3, 4]);
        v["selector_order"] = json!([1, 3, 4]);
        v["extra_ids"] = json!([4]);
        v["settings"]["server_strategy"] = json!("prefer_ipv4");
        let output = run(&v);
        assert_eq!(output["status"], "SUCCESS", "{output}");
        let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
        assert_outbound_tag_integrity(&config);
        let dns = &config["dns"];
        assert!(dns["servers"][0].get("detour").is_none());
        assert!(dns["servers"][1].get("detour").is_none());
        assert_eq!(dns["servers"][2]["domain_resolver"], "dns-direct");
        assert!(dns["rules"].as_array().unwrap().iter().any(|r| r["server"] == "dns-direct" && r.to_string().contains("dns.example")));
        v["for_test"] = json!(true);
        let output = run(&v);
        let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
        assert_eq!(config["dns"]["final"], "dns-direct");
        assert_eq!(config["dns"]["servers"].as_array().unwrap().len(), 2);
    }
    #[test]
    fn wireguard_final_targets_endpoint_in_standalone_selector_and_chain() {
        for mode in 0..3 {
            let mut v = if mode == 0 { request() } else { reused_global_request(mode == 1, false) };
            v["profiles"][0]["outbound"] = json!({"kind":"WireGuard","server":"127.0.0.1","port":51820,
                "local_address":"10.77.0.2/32","private_key":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                "public_key":"AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=","pre_shared_key":"","mtu":1420,"reserved":""});
            if mode == 2 { v["selected"] = json!(3); v["extra_ids"] = json!([]); }
            let output = run(&v);
            assert_eq!(output["status"], "SUCCESS", "{output}");
            let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
            assert_outbound_tag_integrity(&config);
            assert_eq!(config["route"]["final"], "proxy");
            assert_eq!(config["endpoints"].as_array().unwrap().len(), 1);
            assert_eq!(config["dns"]["servers"][2]["detour"], "proxy");
        }
    }
    #[test]
    fn strict_snapshot_schema_and_graph_fail_closed() {
        let valid = request();
        assert_eq!(run(&valid)["status"], "SUCCESS");
        let mut v = valid.clone();
        v["version"] = json!(2);
        assert_eq!(run(&v)["error"], "UNSUPPORTED_VERSION");
        let mut v = valid.clone();
        v["settings"]["unexpected"] = json!(true);
        assert_eq!(run(&v)["error"], "INVALID_CONFIG_SNAPSHOT");
        let mut v = valid.clone();
        v["profiles"]
            .as_array_mut()
            .unwrap()
            .push(valid["profiles"][0].clone());
        assert_eq!(run(&v)["error"], "DUPLICATE_SNAPSHOT_ID");
        let mut v = valid.clone();
        v["profiles"][0]["chain"] = json!([1]);
        assert_eq!(run(&v)["error"], "CYCLIC_OR_EXCESSIVE_CHAIN");
        let mut v = valid.clone();
        v["profiles"][0]["chain"] = json!([999]);
        assert_eq!(run(&v)["error"], "EMPTY_CHAIN");
        let mut v = valid.clone();
        v["profiles"][0]["outbound"]["port"] = json!("1080");
        assert_eq!(run(&v)["status"], "ERROR");
        assert_eq!(
            serde_json::from_slice::<Value>(&generate(b"{\"mode\":")).unwrap()["status"],
            "ERROR"
        );
    }
    #[test]
    fn merge_order_preserves_replace_prepend_append_and_deep_maps() {
        let mut dst = json!({"route":{"rules":[0],"auto_detect_interface":true}});
        let src: Value =
            serde_json::from_str(r#"{"route":{"rules":[1],"+rules":[2],"rules+":[3]}}"#).unwrap();
        merge(&mut dst, &src);
        assert_eq!(
            dst,
            json!({"route":{"rules":[2,1,3],"auto_detect_interface":true}})
        );
        let src: Value = serde_json::from_str(r#"{"route":{"+rules":[4],"rules":[5]}}"#).unwrap();
        merge(&mut dst, &src);
        assert_eq!(dst["route"]["rules"], json!([5]));
    }
    #[test]
    fn address_sets_are_not_projected_to_dns() {
        let mut v = request();
        let mut r = rule();
        r["outbound"] = json!(-1);
        r["domains"] = json!("full:example.com");
        r["ip"] = json!("10.0.0.0/8");
        r["rule_sets"] = json!([rule_set("geoip-cn", "destination")]);
        v["rules"] = json!([r]);
        let output = run(&v);
        let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
        let rules = config["dns"]["rules"].as_array().unwrap();
        assert!(!rules.iter().any(|v| v["action"] == "evaluate"));
        assert_eq!(output["warnings"], json!([[1,"DNS_RULE_NOT_PROJECTED"]]));
        assert_eq!(config["route"]["rule_set"][0]["url"], "https://example.net/geoip-cn.srs");
        assert_eq!(config["http_clients"], json!([{"tag":"default-http-client"}]));
    }
    #[test]
    fn generated_direct_dns_uses_default_dialer_for_all_transports() {
        for (address, kind) in [
            ("local", "local"), ("1.1.1.1", "udp"),
            ("udp://1.1.1.1", "udp"), ("tcp://1.1.1.1", "tcp"),
            ("tls://dns.example", "tls"), ("quic://dns.example", "quic"),
            ("https://223.5.5.5/dns-query", "https"),
            ("https://dns.example/dns-query", "https"), ("h3://dns.example", "h3"),
        ] {
            for for_test in [false, true] {
                let mut v = request();
                v["for_test"] = json!(for_test);
                v["settings"]["direct_dns"] = json!(address);
                let output = run(&v);
                assert_eq!(output["status"], "SUCCESS");
                let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
                let servers = config["dns"]["servers"].as_array().unwrap();
                assert_eq!(servers.len(), if for_test { 2 } else { 3 });
                assert_eq!(servers[0], json!({"tag":"dns-local","type":"local"}));
                assert_eq!(servers[1]["type"], kind);
                assert!(servers[1].get("detour").is_none(), "{address}: {}", servers[1]);
                if kind != "local" {
                    assert_eq!(servers[1]["domain_resolver"], "dns-local");
                }
                if !for_test {
                    assert_eq!(servers[2], json!({"tag":"dns-remote","type":"https","server":"dns.example","domain_resolver":"dns-direct","detour":"proxy"}));
                }
            }
        }
    }
    #[test]
    fn custom_dns_and_http_client_detours_survive_final_merge() {
        let mut v = request();
        let custom = json!({
            "dns":{"servers":[{"tag":"custom-dns","type":"https","server":"1.1.1.1","detour":"proxy"}],"final":"custom-dns"},
            "http_clients":[{"tag":"custom-http","detour":"direct"}],
            "route":{"final":"bypass"}
        });
        for profile_level in [false, true] {
            v["settings"]["custom"] = if profile_level { Value::Null } else { custom.clone() };
            v["profiles"][0]["custom_config"] = if profile_level { custom.clone() } else { Value::Null };
            let output = run(&v);
            assert_eq!(output["status"], "SUCCESS");
            let config: Value = serde_json::from_str(output["config"].as_str().unwrap()).unwrap();
            assert_eq!(config["dns"]["servers"], custom["dns"]["servers"]);
            assert_eq!(config["http_clients"], custom["http_clients"]);
            assert_eq!(config["route"]["final"], "bypass");
        }
    }
    #[test]
    fn missing_outbound_warns_only_for_nonempty_route_rule() {
        let mut v = request();
        v["rules"] = json!([rule()]);
        assert_eq!(run(&v)["warnings"], json!([]));
        v["rules"][0]["domains"] = json!("example.com");
        assert_eq!(run(&v)["warnings"], json!([[1, "MISSING_RULE_OUTBOUND"]]));
    }
    #[test]
    fn passthrough_keeps_text_exactly_and_rejects_unknown_fields() {
        let mut v = json!({"mode":"passthrough","version":1,"selected":42,"full_config":"\n { /* full user syntax */ }  "});
        assert_eq!(run(&v)["config"], v["full_config"]);
        v["extra"] = json!(1);
        assert_eq!(run(&v)["status"], "ERROR");
    }
}
