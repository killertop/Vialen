use crate::model::CanonicalNode;

pub fn parse_vless(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("vless://") {
        return Err("invalid scheme");
    }
    let normalized = super::candidate_url::normalize(url)?;
    let parsed = super::url::parse_url(&normalized)?;
    if !super::url::percent_decode(parsed.password)
        .trim()
        .is_empty()
    {
        return super::vmess::parse_vmess_v2fly(url);
    }
    super::ducksoft::parse(url, "vless")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_vless_ducksoft_basic() {
        let uri = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@vless.example.com:443?encryption=none&security=reality&sni=yahoo.com&pbk=dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df9&sid=0123456789abcdef&type=tcp&flow=xtls-rprx-vision#SampleVLESSReality";
        let node = parse_vless(uri).unwrap();
        assert_eq!(node.protocol, "vless");
        assert_eq!(node.server, "vless.example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.username, "b831381d-6324-4d53-ad4f-8cda48b30811");
        assert_eq!(node.name, "SampleVLESSReality");
        assert!(node.tls.as_ref().unwrap().enabled);
        assert_eq!(node.tls.as_ref().unwrap().server_name, "yahoo.com");
        assert_eq!(
            node.tls.as_ref().unwrap().reality_public_key,
            "dc1136b69c4a85590ee856ec8adcfcae6361a46cf7f8a70df9"
        );
        assert_eq!(
            node.tls.as_ref().unwrap().reality_short_id,
            "0123456789abcdef"
        );
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "tcp");
        assert_eq!(node.extra.as_ref().unwrap().alter_id, -1);
        assert_eq!(node.extra.as_ref().unwrap().encryption, "xtls-rprx-vision");
    }

    #[test]
    fn test_vless_ws_tls() {
        let uri = "vless://a1b2c3d4-e5f6-7890-abcd-ef1234567890@192.168.1.1:8443?type=ws&security=tls&host=wshost.com&path=/wspath&sni=snihost.com&alpn=h2,http/1.1&fp=chrome&ed=2048&eh=Sec-WebSocket-Protocol&packetEncoding=xudp#VLESS-WS-TLS";
        let node = parse_vless(uri).unwrap();
        assert_eq!(node.protocol, "vless");
        assert_eq!(node.server, "192.168.1.1");
        assert_eq!(node.port, 8443);
        assert_eq!(node.username, "a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        assert_eq!(node.name, "VLESS-WS-TLS");
        assert!(node.tls.as_ref().unwrap().enabled);
        assert_eq!(node.tls.as_ref().unwrap().server_name, "snihost.com");
        assert_eq!(node.tls.as_ref().unwrap().alpn, vec!["h2", "http/1.1"]);
        assert_eq!(node.tls.as_ref().unwrap().utls_fingerprint, "chrome");
        assert_eq!(node.transport.as_ref().unwrap().transport_type, "ws");
        assert_eq!(node.transport.as_ref().unwrap().host, "wshost.com");
        assert_eq!(node.transport.as_ref().unwrap().path, "/wspath");
        assert_eq!(node.transport.as_ref().unwrap().max_early_data, 2048);
        assert_eq!(
            node.transport.as_ref().unwrap().early_data_header_name,
            "Sec-WebSocket-Protocol"
        );
        assert_eq!(node.transport.as_ref().unwrap().packet_encoding, 2);
    }
}
