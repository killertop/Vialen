mod android_base64;
pub mod base64;
mod candidate_url;
mod ducksoft;
pub mod hysteria;
pub mod shadowsocks;
pub mod socks;
pub mod subscription;
pub mod trojan;
pub mod tuic;
pub mod url;
pub mod vless;
pub mod vmess;

use crate::model::node::CanonicalNode;
pub use hysteria::{parse_hysteria1, parse_hysteria2};
pub use shadowsocks::{CanonicalProxy, parse_shadowsocks};
pub use socks::parse_socks;
pub use subscription::decode_subscription_lines;
pub use trojan::parse_trojan;
pub use tuic::parse_tuic;
pub use vless::parse_vless;
pub use vmess::parse_vmess;

fn utf16_len(s: &str) -> usize {
    s.encode_utf16().count()
}

pub fn parse_proxy(uri: &str) -> Result<CanonicalNode, &'static str> {
    if uri.starts_with("ss://") {
        parse_shadowsocks(uri)
    } else if uri.starts_with("socks://")
        || uri.starts_with("socks4://")
        || uri.starts_with("socks4a://")
        || uri.starts_with("socks5://")
    {
        parse_socks(uri)
    } else if uri.starts_with("trojan://") {
        parse_trojan(uri)
    } else if uri.starts_with("tuic://") {
        parse_tuic(uri)
    } else if uri.starts_with("hysteria://") {
        parse_hysteria1(uri)
    } else if uri.starts_with("hysteria2://") || uri.starts_with("hy2://") {
        parse_hysteria2(uri)
    } else if uri.starts_with("vless://") {
        parse_vless(uri)
    } else if uri.starts_with("vmess://") {
        parse_vmess(uri)
    } else {
        Err("unsupported protocol")
    }
}

pub fn serialize_canonical_node(proxy: &CanonicalNode) -> String {
    let port_str = proxy.port.to_string();
    let sni = proxy
        .tls
        .as_ref()
        .map(|t| t.server_name.as_str())
        .unwrap_or("");
    let alpn = proxy
        .tls
        .as_ref()
        .map(|t| t.alpn.join(","))
        .unwrap_or_default();
    let allow_insecure = if proxy
        .tls
        .as_ref()
        .map(|t| t.allow_insecure)
        .unwrap_or(false)
    {
        "1"
    } else {
        "0"
    };
    let disable_sni = if proxy.tls.as_ref().map(|t| t.disable_sni).unwrap_or(false) {
        "1"
    } else {
        "0"
    };
    let transport_type = proxy
        .transport
        .as_ref()
        .map(|t| t.transport_type.as_str())
        .unwrap_or("");
    let transport_host = proxy
        .transport
        .as_ref()
        .map(|t| t.host.as_str())
        .unwrap_or("");
    let transport_path = proxy
        .transport
        .as_ref()
        .map(|t| t.path.as_str())
        .unwrap_or("");

    let certificates = proxy
        .tls
        .as_ref()
        .map(|t| t.certificates.as_str())
        .unwrap_or("");
    let utls_fingerprint = proxy
        .tls
        .as_ref()
        .map(|t| t.utls_fingerprint.as_str())
        .unwrap_or("");
    let reality_public_key = proxy
        .tls
        .as_ref()
        .map(|t| t.reality_public_key.as_str())
        .unwrap_or("");
    let reality_short_id = proxy
        .tls
        .as_ref()
        .map(|t| t.reality_short_id.as_str())
        .unwrap_or("");
    let early_data_header_name = proxy
        .transport
        .as_ref()
        .map(|t| t.early_data_header_name.as_str())
        .unwrap_or("");
    let max_early_data_str = proxy
        .transport
        .as_ref()
        .map(|t| t.max_early_data.to_string())
        .unwrap_or_else(|| "0".to_string());
    let packet_encoding_str = proxy
        .transport
        .as_ref()
        .map(|t| t.packet_encoding.to_string())
        .unwrap_or_else(|| "0".to_string());

    let (
        congestion_control,
        udp_relay_mode,
        auth_payload,
        server_ports,
        obfs_type,
        obfs_password,
        up_mbps_str,
        down_mbps_str,
        disable_chrome_parrot,
        bbr_profile,
        hop_interval_max_str,
        obfs_min_packet_size_str,
        obfs_max_packet_size_str,
        alter_id_str,
        encryption,
    ) = if let Some(ref e) = proxy.extra {
        (
            e.congestion_control.as_str(),
            e.udp_relay_mode.as_str(),
            e.auth_payload.as_str(),
            e.mport.as_str(),
            e.obfs_type.as_str(),
            e.obfs_password.as_str(),
            e.upload_mbps.to_string(),
            e.download_mbps.to_string(),
            if e.disable_chrome_parrot { "1" } else { "0" },
            e.bbr_profile.as_str(),
            e.hop_interval_max.to_string(),
            e.obfs_min_packet_size.to_string(),
            e.obfs_max_packet_size.to_string(),
            e.alter_id.to_string(),
            e.encryption.as_str(),
        )
    } else {
        (
            "",
            "",
            "",
            "",
            "",
            "",
            "0".to_string(),
            "0".to_string(),
            "0",
            "",
            "0".to_string(),
            "512".to_string(),
            "1200".to_string(),
            "0".to_string(),
            "",
        )
    };

    let tls_enabled = if proxy.tls.as_ref().map(|t| t.enabled).unwrap_or(false) {
        "1"
    } else {
        "0"
    };
    let transport_service_name = proxy
        .transport
        .as_ref()
        .map(|t| t.service_name.as_str())
        .unwrap_or("");

    let mut payload = String::with_capacity(768);
    payload.push_str("SUCCESS\n");
    let fields: [&str; 38] = [
        &proxy.protocol,
        &proxy.server,
        &port_str,
        &proxy.username,
        &proxy.password,
        &proxy.plugin,
        &proxy.name,
        sni,
        &alpn,
        allow_insecure,
        disable_sni,
        transport_type,
        transport_host,
        transport_path,
        congestion_control,
        udp_relay_mode,
        auth_payload,
        server_ports,
        obfs_type,
        obfs_password,
        &up_mbps_str,
        &down_mbps_str,
        certificates,
        utls_fingerprint,
        reality_public_key,
        reality_short_id,
        early_data_header_name,
        &max_early_data_str,
        &packet_encoding_str,
        disable_chrome_parrot,
        bbr_profile,
        &hop_interval_max_str,
        &obfs_min_packet_size_str,
        &obfs_max_packet_size_str,
        tls_enabled,
        transport_service_name,
        &alter_id_str,
        encryption,
    ];
    for f in fields {
        use std::fmt::Write;
        let _ = write!(payload, "{}:{}", utf16_len(f), f);
    }
    payload
}

pub fn parse_proxy_to_canonical(uri: &str) -> String {
    match parse_proxy(uri) {
        Ok(proxy) => serialize_canonical_node(&proxy),
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

pub use parse_proxy_to_canonical as parse_proxy_uri;
