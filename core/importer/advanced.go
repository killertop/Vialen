package importer

import (
	"github.com/killertop/Vialen/core/profile"
	"time"
)

func advancedFields(f *fields, p *profile.Profile, clash bool) {
	ukey, mkey := "udp_over_tcp", "multiplex"
	if clash {
		ukey = "udp-over-tcp"
		mkey = "smux"
	}
	if f.has(ukey) {
		if _, ok := f.m[ukey].(bool); ok {
			p.UDPOverTCP = &profile.UDPOverTCP{Enabled: f.boolean(ukey)}
		} else {
			u := f.child(ukey)
			p.UDPOverTCP = &profile.UDPOverTCP{Enabled: u.boolean("enabled"), Version: uint32(u.uint("version", 2))}
			f.accept(u)
		}
	}
	if f.has(mkey) {
		m := f.child(mkey)
		maxc, mins, maxs := "max_connections", "min_streams", "max_streams"
		if clash {
			maxc = "max-connections"
			mins = "min-streams"
			maxs = "max-streams"
		}
		p.Multiplex = &profile.Multiplex{Enabled: m.boolean("enabled"), Protocol: m.str("protocol"), MaxConnections: uint32(m.uint(maxc, 65535)), MinStreams: uint32(m.uint(mins, 65535)), MaxStreams: uint32(m.uint(maxs, 65535)), Padding: m.boolean("padding")}
		f.accept(m)
	}
}
func durationSeconds(f *fields, k string) uint32 {
	if !f.has(k) {
		return 0
	}
	d, e := time.ParseDuration(f.str(k))
	if e != nil || d < 0 || d > 24*time.Hour || d%time.Second != 0 {
		f.err = fieldError(k)
		return 0
	}
	return uint32(d / time.Second)
}
