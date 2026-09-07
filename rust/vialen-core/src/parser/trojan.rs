use crate::model::CanonicalNode;

pub fn parse_trojan(url: &str) -> Result<CanonicalNode, &'static str> {
    if !url.starts_with("trojan://") {
        return Err("invalid scheme");
    }
    super::ducksoft::parse(url, "trojan")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_trojan_basic() {
        let uri =
            "trojan://mypassword@example.com:443?peer=sni.example.com&allowInsecure=1#TrojanNode";
        let node = parse_trojan(uri).unwrap();
        assert_eq!(node.protocol, "trojan");
        assert_eq!(node.server, "example.com");
        assert_eq!(node.port, 443);
        assert_eq!(node.password, "mypassword");
        assert_eq!(node.name, "TrojanNode");
        let tls = node.tls.unwrap();
        assert_eq!(tls.server_name, "sni.example.com");
        assert!(tls.allow_insecure);
    }
}
