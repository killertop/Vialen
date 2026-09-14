package procfs

import "testing"

func TestOwnerAddress(t *testing.T) {
	for _, tc := range []struct {
		host string
		port int32
		want string
	}{
		{"127.0.0.1", 1, "127.0.0.1:1"}, {"2001:db8::1", 65535, "[2001:db8::1]:65535"},
		{"::", 0, "[::]:0"}, {"bad", 80, ""}, {"::1", -1, ""}, {"::1", 65536, ""},
	} {
		got, err := OwnerAddress(tc.host, tc.port)
		if tc.want == "" {
			if err == nil {
				t.Fatalf("accepted invalid address/port")
			}
			continue
		}
		if err != nil || got.String() != tc.want {
			t.Fatalf("got %v %v want %s", got, err, tc.want)
		}
	}
}
