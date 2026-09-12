package profile

func validateAdvanced(p Profile) error {
	if u := p.UDPOverTCP; u != nil {
		if p.Type != "socks" && p.Type != "shadowsocks" {
			return invalid("udp_over_tcp", "unsupported_for_protocol")
		}
		if u.Version > 2 {
			return invalid("udp_over_tcp.version", "out_of_range")
		}
	}
	if m := p.Multiplex; m != nil {
		switch p.Type {
		case "shadowsocks", "vmess", "vless", "trojan":
		default:
			return invalid("multiplex", "unsupported_for_protocol")
		}
		switch m.Protocol {
		case "", "h2mux", "smux", "yamux":
		default:
			return invalid("multiplex.protocol", "invalid_protocol")
		}
		if m.MaxConnections > 65535 || m.MinStreams > 65535 || m.MaxStreams > 65535 {
			return invalid("multiplex", "out_of_range")
		}
	}
	if p.TLS != nil && p.TLS.ECH != nil {
		e := p.TLS.ECH
		if p.TLS.Reality != nil {
			return invalid("tls.ech", "conflicts_with_reality")
		}
		if e.QueryServerName != "" {
			if _, err := NormalizeServer(e.QueryServerName); err != nil {
				return invalid("tls.ech.query_server_name", "invalid_host")
			}
		}
		for _, c := range e.Config {
			if c == "" {
				return invalid("tls.ech.config", "empty_config")
			}
		}
	}
	if h := p.Hysteria2; h != nil {
		switch h.BBRProfile {
		case "", "standard", "conservative", "aggressive":
		default:
			return invalid("hysteria2.bbr_profile", "invalid_profile")
		}
		if h.HopInterval > 86400 || h.HopIntervalMax > 86400 || h.HopIntervalMax != 0 && h.HopIntervalMax < h.HopInterval {
			return invalid("hysteria2.hop_interval", "invalid_range")
		}
		if o := h.Obfs; o != nil {
			if o.Type != "gecko" && (o.MinPacketSize != 0 || o.MaxPacketSize != 0) {
				return invalid("hysteria2.obfs", "gecko_options_required")
			}
			if o.MinPacketSize > 65535 || o.MaxPacketSize > 65535 || o.MaxPacketSize != 0 && o.MinPacketSize > o.MaxPacketSize {
				return invalid("hysteria2.obfs", "invalid_packet_size")
			}
		}
	}
	return nil
}
