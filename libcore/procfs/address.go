package procfs

import (
	"fmt"
	"net/netip"
)

// OwnerAddress accepts the platform's bare IP literal and preserves port zero
// for unconnected UDP/wildcard endpoints, but never truncates an invalid port.
func OwnerAddress(host string, port int32) (netip.AddrPort, error) {
	address, err := netip.ParseAddr(host)
	if err != nil {
		return netip.AddrPort{}, fmt.Errorf("invalid connection address")
	}
	if port < 0 || port > 65535 {
		return netip.AddrPort{}, fmt.Errorf("invalid connection port")
	}
	return netip.AddrPortFrom(address, uint16(port)), nil
}
