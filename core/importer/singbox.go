package importer

import (
	"github.com/killertop/Vialen/core/profile"
	"math"
	"strings"
)

func parseSingbox(text string) (Result, error) {
	out := result("singbox")
	v, e := parseJSON(text)
	if e != nil {
		return out, e
	}
	var entries []any
	switch root := v.(type) {
	case []any:
		entries = root
	case map[string]any:
		if _, ok := root["profiles"]; ok {
			return parseProfilesValue(root)
		}
		found := false
		for _, key := range []string{"outbounds", "endpoints"} {
			if value, ok := root[key]; ok {
				found = true
				a, ok := value.([]any)
				if !ok {
					return out, bad("INVALID_SINGBOX", "outbounds and endpoints must be arrays")
				}
				entries = append(entries, a...)
			}
		}
		if !found {
			return out, bad("INVALID_SINGBOX", "Expected outbounds or endpoints; full configurations are not profiles")
		}
	default:
		return out, bad("INVALID_SINGBOX", "Expected an object or an array of nodes")
	}
	if e = sourceLimit(len(entries)); e != nil {
		return out, e
	}
	expanded := 0
	for _, v := range entries {
		expanded++
		if m, ok := v.(map[string]any); ok {
			if m["type"] == "wireguard" {
				if peers, ok := m["peers"].([]any); ok {
					expanded += len(peers) - 1
				}
			}
		}
	}
	if e = sourceLimit(expanded); e != nil {
		return out, e
	}
	for i, v := range entries {
		m, e := object(v)
		if e != nil {
			out.add(i, profile.Profile{}, e)
			continue
		}
		if m["type"] == "wireguard" {
			if peers, exists := m["peers"]; exists {
				a, ok := peers.([]any)
				if !ok || len(a) == 0 {
					out.add(i, profile.Profile{}, fieldError("peers"))
					continue
				}
				for pi, peer := range a {
					p, e := singboxWireGuardPeer(m, peer)
					if len(a) > 1 && p.Name != "" {
						p.Name += " / peer " + itoa(pi+1)
					}
					out.add(i, p, e)
				}
				continue
			}
		}
		p, e := singboxProfile(m)
		out.add(i, p, e)
	}
	return out, nil
}
func singboxProfile(m map[string]any) (profile.Profile, error) {
	f := fields{m: m}
	p := profile.Profile{Type: f.str("type"), Name: f.str("tag"), Server: f.str("server"), Port: uint16(f.uint("server_port", 65535))}
	switch p.Type {
	case "socks":
		version := f.str("version")
		if version == "" {
			version = "5"
		}
		p.Socks = &profile.Socks{Version: version, Username: f.str("username"), Password: f.str("password")}
	case "http":
		p.HTTP = &profile.HTTP{Username: f.str("username"), Password: f.str("password")}
	case "shadowsocks":
		p.Shadowsocks = &profile.Shadowsocks{Method: f.str("method"), Password: f.str("password"), Plugin: f.str("plugin"), PluginOptions: f.str("plugin_opts")}
	case "vmess":
		p.VMess = &profile.VMess{UUID: f.str("uuid"), Security: f.str("security"), AlterID: uint32(f.uint("alter_id", math.MaxUint32)), PacketEncoding: f.str("packet_encoding")}
	case "vless":
		p.VLESS = &profile.VLESS{UUID: f.str("uuid"), Flow: f.str("flow"), PacketEncoding: f.str("packet_encoding")}
	case "trojan":
		p.Trojan = &profile.Trojan{Password: f.str("password")}
	case "hysteria2":
		hy := &profile.Hysteria2{HopInterval: durationSeconds(&f, "hop_interval"), HopIntervalMax: durationSeconds(&f, "hop_interval_max"), BBRProfile: f.str("bbr_profile"), DisableChromeParrot: f.boolean("disable_chrome_parrot"), Password: f.str("password"), UpMbps: uint32(f.uint("up_mbps", math.MaxUint32)), DownMbps: uint32(f.uint("down_mbps", math.MaxUint32)), ServerPorts: f.strings("server_ports")}
		if f.has("obfs") {
			obfs := f.child("obfs")
			hy.Obfs = &profile.Obfs{MinPacketSize: uint32(obfs.uint("min_packet_size", 65535)), MaxPacketSize: uint32(obfs.uint("max_packet_size", 65535)), Type: obfs.str("type"), Password: obfs.str("password")}
			f.accept(obfs)
		}
		p.Hysteria2 = hy
	case "tuic":
		p.TUIC = &profile.TUIC{UUID: f.str("uuid"), Password: f.str("password"), CongestionControl: f.str("congestion_control"), UDPRelayMode: f.str("udp_relay_mode"), ZeroRTTHandshake: f.boolean("zero_rtt_handshake")}
	case "shadowtls":
		p.Type = "shadowtls"
		p.ShadowTLS = &profile.ShadowTLS{Version: uint32(f.uint("version", 3)), Password: f.str("password")}
	case "anytls":
		p.AnyTLS = &profile.AnyTLS{Password: f.str("password")}
	case "wireguard":
		p.WireGuard = &profile.WireGuard{PrivateKey: f.str("private_key"), PublicKey: f.str("peer_public_key"), PreSharedKey: f.str("pre_shared_key"), Address: listable(&f, "local_address"), AllowedIPs: listable(&f, "allowed_ips"), MTU: uint32(f.uint("mtu", math.MaxUint32)), Reserved: reserved(&f, "reserved")}
	case "direct", "block", "dns", "selector", "urltest":
		return p, bad("NON_PROXY_ENTRY", "Routing and selection entries are not standalone profiles")
	default:
		return p, bad("UNSUPPORTED_PROTOCOL", "Unsupported sing-box node type")
	}
	advancedFields(&f, &p, false)
	if f.has("detour") && f.str("detour") != "" {
		return p, bad("DEPENDENT_NODE", "Detour nodes require an explicit chain and cannot be imported independently")
	}
	if f.has("tls") {
		t := f.child("tls")
		// Refuse constraints the profile model cannot preserve.
		for key := range t.m {
			switch key {
			case "disable_sni", "enabled", "server_name", "insecure", "alpn", "ech", "certificate", "utls", "reality":
			default:
				return p, bad("UNSUPPORTED_TLS_FIELD", "Unsupported TLS field: tls."+key)
			}
		}
		tls := &profile.TLS{DisableSNI: t.boolean("disable_sni"), Enabled: t.boolean("enabled"), ServerName: t.str("server_name"), Insecure: t.boolean("insecure"), ALPN: listable(t, "alpn")}
		if t.has("ech") {
			e := t.child("ech")
			tls.ECH = &profile.ECH{Enabled: e.boolean("enabled"), Config: listable(e, "config"), QueryServerName: e.str("query_server_name")}
			t.accept(e)
		}
		if t.has("certificate") {
			tls.Certificate = strings.Join(listable(t, "certificate"), "\n")
		}
		if t.has("utls") {
			u := t.child("utls")
			if u.boolean("enabled") {
				tls.Fingerprint = u.str("fingerprint")
			}
			t.accept(u)
		}
		if t.has("reality") {
			r := t.child("reality")
			if r.boolean("enabled") {
				tls.Reality = &profile.Reality{PublicKey: r.str("public_key"), ShortID: r.str("short_id")}
			}
			t.accept(r)
		}
		if tls.Enabled {
			p.TLS = tls
		}
		f.accept(t)
	}
	if f.has("transport") {
		tr := f.child("transport")
		t := &profile.Transport{Type: tr.str("type"), Host: listable(tr, "host"), Path: tr.str("path"), ServiceName: tr.str("service_name"), MaxEarlyData: uint32(tr.uint("max_early_data", math.MaxUint32)), EarlyDataHeaderName: tr.str("early_data_header_name")}
		if tr.has("headers") {
			headers := tr.child("headers")
			for k := range headers.m {
				values := listable(headers, k)
				if strings.EqualFold(k, "host") {
					if len(t.Host) != 0 {
						headers.err = fieldError("duplicate transport host")
					}
					t.Host = values
				} else {
					if t.Headers == nil {
						t.Headers = map[string][]string{}
					}
					t.Headers[k] = values
				}
			}
			tr.accept(headers)
		}
		p.Transport = t
		f.accept(tr)
	}
	if f.err != nil {
		return p, f.err
	}
	server, e := profile.NormalizeServer(p.Server)
	if e != nil {
		return p, bad("INVALID_SERVER", "Invalid proxy server")
	}
	p.Server = server
	return p, nil
}
func singboxWireGuardPeer(endpoint map[string]any, value any) (profile.Profile, error) {
	root := fields{m: endpoint}
	if root.has("detour") {
		detour := root.str("detour")
		if root.err != nil {
			return profile.Profile{}, root.err
		}
		if detour != "" {
			return profile.Profile{}, bad("DEPENDENT_NODE", "Detour nodes require an explicit chain and cannot be imported independently")
		}
	}
	m, e := object(value)
	if e != nil {
		return profile.Profile{}, e
	}
	peer := fields{m: m}
	p := profile.Profile{Type: "wireguard", Name: root.str("tag"), Server: peer.str("address"), Port: uint16(peer.uint("port", 65535)), WireGuard: &profile.WireGuard{PrivateKey: root.str("private_key"), PublicKey: peer.str("public_key"), PreSharedKey: peer.str("pre_shared_key"), Address: listable(&root, "address"), AllowedIPs: listable(&peer, "allowed_ips"), MTU: uint32(root.uint("mtu", math.MaxUint32)), Reserved: reserved(&peer, "reserved"), PersistentKeepalive: uint32(peer.uint("persistent_keepalive_interval", 65535))}}
	root.accept(&peer)
	if root.err != nil {
		return p, root.err
	}
	server, e := profile.NormalizeServer(p.Server)
	if e != nil {
		return p, bad("INVALID_SERVER", "Invalid WireGuard peer address")
	}
	p.Server = server
	return p, nil
}
func listable(f *fields, k string) []string {
	if !f.has(k) {
		return nil
	}
	if s, ok := f.m[k].(string); ok {
		return []string{s}
	}
	return f.strings(k)
}
