package libcore

import (
	"github.com/sagernet/sing-box/adapter"
	"strings"
	"syscall"
	"testing"
)

func TestLegacyOwnerRejectsBothInvalidEndpoints(t *testing.T) {
	old := useProcfs
	useProcfs = true
	defer func() { useProcfs = old }()
	for _, tc := range []struct {
		source, destination         string
		sourcePort, destinationPort int32
		prefix                      string
	}{
		{"bad", "::1", 1, 1, "source:"},
		{"::1", "bad", 1, 1, "destination:"},
		{"::1", "::1", -1, 1, "source:"},
		{"127.0.0.1", "::1", 1, 65536, "destination:"},
	} {
		owner, err := (&boxPlatformInterfaceWrapper{}).FindConnectionOwner(&adapter.FindConnectionOwnerRequest{
			IpProtocol: syscall.IPPROTO_UDP, SourceAddress: tc.source, DestinationAddress: tc.destination,
			SourcePort: tc.sourcePort, DestinationPort: tc.destinationPort,
		})
		if owner != nil || err == nil || !strings.HasPrefix(err.Error(), tc.prefix) {
			t.Fatalf("invalid endpoint reached procfs: %v %v", owner, err)
		}
	}
}
