package importer

import (
	"github.com/killertop/Vialen/core/profile"
	"net"
	"path/filepath"
	"strconv"
	"strings"
)

type iniSection struct {
	name   string
	values map[string]string
}

func itoa(n int) string { return strconv.Itoa(n) }
func parseWireGuard(text, file string) (Result, error) {
	out := result("wireguard")
	var iface *iniSection
	peers := []*iniSection{}
	var current *iniSection
	for _, raw := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		line := strings.TrimSpace(raw)
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" || strings.HasPrefix(line, ";") {
			continue
		}
		if strings.HasPrefix(line, "[") {
			switch line {
			case "[Interface]":
				if iface != nil {
					return out, bad("INVALID_WIREGUARD", "Only one Interface section is allowed")
				}
				iface = &iniSection{"Interface", map[string]string{}}
				current = iface
			case "[Peer]":
				current = &iniSection{"Peer", map[string]string{}}
				peers = append(peers, current)
				if len(peers) > MaxProfiles {
					return out, bad("TOO_MANY_PROFILES", "WireGuard exceeds 10000 peers")
				}
			default:
				return out, bad("INVALID_WIREGUARD", "Unsupported INI section")
			}
			continue
		}
		if current == nil {
			return out, bad("INVALID_WIREGUARD", "Options must belong to Interface or Peer")
		}
		k, v, ok := strings.Cut(line, "=")
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		if !ok || k == "" || v == "" {
			return out, bad("INVALID_WIREGUARD", "Expected a nonempty key=value option")
		}
		allowed := map[string]bool{"PublicKey": true, "PresharedKey": true, "AllowedIPs": true, "Endpoint": true, "PersistentKeepalive": true}
		if current.name == "Interface" {
			allowed = map[string]bool{"PrivateKey": true, "Address": true, "MTU": true, "DNS": true, "ListenPort": true, "Table": true, "PreUp": true, "PostUp": true, "PreDown": true, "PostDown": true, "SaveConfig": true, "FwMark": true}
		}
		if !allowed[k] {
			return out, bad("INVALID_WIREGUARD", "Unsupported INI option")
		}
		if old, exists := current.values[k]; exists {
			if k == "Address" || k == "AllowedIPs" {
				v = old + "," + v
			} else {
				return out, bad("INVALID_WIREGUARD", "Duplicate singleton option")
			}
		}
		current.values[k] = v
	}
	if iface == nil {
		return out, bad("INVALID_WIREGUARD", "Missing Interface section")
	}
	if len(peers) == 0 {
		return out, bad("INVALID_WIREGUARD", "Missing Peer section")
	}
	addresses := commaList(iface.values["Address"])
	if len(addresses) == 0 || iface.values["PrivateKey"] == "" {
		return out, bad("INVALID_WIREGUARD", "Interface requires Address and PrivateKey")
	}
	mtu, e := iniUint(iface.values["MTU"], "MTU")
	if e != nil {
		return out, e
	}
	for _, k := range []string{"PreUp", "PostUp", "PreDown", "PostDown", "Table", "DNS", "ListenPort", "SaveConfig", "FwMark"} {
		if _, exists := iface.values[k]; exists {
			out.Issues = append(out.Issues, Issue{Index: -1, Code: "INTERFACE_SETTING_OMITTED", Message: "Interface setting " + k + " is not part of a proxy profile", Severity: SeverityWarning})
		}
	}
	name := strings.TrimSuffix(filepath.Base(file), filepath.Ext(file))
	if file == "" {
		name = ""
	}
	for i, peer := range peers {
		host, port, e := net.SplitHostPort(peer.values["Endpoint"])
		if e != nil {
			out.add(i, profile.Profile{}, fieldError("Endpoint"))
			continue
		}
		n, e := strconv.ParseUint(port, 10, 16)
		if e != nil || n == 0 {
			out.add(i, profile.Profile{}, fieldError("Endpoint port"))
			continue
		}
		server, e := profile.NormalizeServer(host)
		if e != nil {
			out.add(i, profile.Profile{}, fieldError("Endpoint address"))
			continue
		}
		keepalive, e := iniUint(peer.values["PersistentKeepalive"], "PersistentKeepalive")
		p := profile.Profile{Type: "wireguard", Name: name, Server: server, Port: uint16(n), WireGuard: &profile.WireGuard{PrivateKey: iface.values["PrivateKey"], PublicKey: peer.values["PublicKey"], PreSharedKey: peer.values["PresharedKey"], Address: append([]string(nil), addresses...), AllowedIPs: commaList(peer.values["AllowedIPs"]), MTU: mtu, PersistentKeepalive: keepalive}}
		if len(peers) > 1 && p.Name != "" {
			p.Name += " / peer " + itoa(i+1)
		}
		out.add(i, p, e)
	}
	return out, nil
}
func commaList(s string) []string {
	out := []string{}
	for _, v := range strings.Split(s, ",") {
		if v = strings.TrimSpace(v); v != "" {
			out = append(out, v)
		}
	}
	return out
}
func iniUint(s, field string) (uint32, error) {
	if s == "" {
		return 0, nil
	}
	n, e := strconv.ParseUint(s, 10, 32)
	if e != nil || field == "PersistentKeepalive" && n > 65535 {
		return 0, fieldError(field)
	}
	return uint32(n), nil
}
