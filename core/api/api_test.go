package api

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestTransportBoundary(t *testing.T) {
	input, _ := json.Marshal(map[string]string{"text": "socks5://example.com:1080", "format": "links"})
	data, err := Execute("import", input)
	if err != nil || !strings.Contains(string(data), `"server":"example.com"`) {
		t.Fatalf("%s %v", data, err)
	}
	for _, input := range [][]byte{
		[]byte(`{"text":"x","unknown":true}`), []byte(`{"text":"x"} {}`), {0xff},
		[]byte(strings.Repeat(" ", MaxRequestBytes+1)),
	} {
		if _, err := Import(input); err == nil {
			t.Fatal("invalid request accepted")
		}
	}
	if _, err := Execute("unknown", nil); err == nil {
		t.Fatal("unknown operation accepted")
	}
	if _, err := ValidateProfileExportForTest(); err != nil {
		t.Fatal(err)
	}
}

func ValidateProfileExportForTest() ([]byte, error) {
	input := []byte(`{"type":"socks","server":"example.com","port":1080,"socks":{"version":"5"}}`)
	if err := ValidateProfiles(append(append([]byte(`{"profiles":[`), input...), []byte(`]}`)...)); err != nil {
		return nil, err
	}
	return ExportProfile(input)
}
