package libcore

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

type dummyPlatformInterface struct{}

func (d *dummyPlatformInterface) AutoDetectInterfaceControl(fd int32) error {
	return nil
}

func (d *dummyPlatformInterface) OpenTun(singTunOptionsJson, tunPlatformOptionsJson string) (int, error) {
	return -1, nil
}

func (d *dummyPlatformInterface) UseProcFS() bool {
	return false
}

func (d *dummyPlatformInterface) FindConnectionOwner(ipProtocol int32, sourceAddress string, sourcePort int32, destinationAddress string, destinationPort int32) (int32, error) {
	return 0, nil
}

func (d *dummyPlatformInterface) PackageNameByUid(uid int32) (string, error) {
	return "", nil
}

func (d *dummyPlatformInterface) UIDByPackageName(packageName string) (int32, error) {
	return 0, nil
}

func (d *dummyPlatformInterface) WIFIState() string {
	return ""
}

func createDummySRS(path string) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return srs.Write(f, option.PlainRuleSet{
		Rules: []option.HeadlessRule{},
	}, C.RuleSetVersionCurrent)
}

func TestGoldenConfigsInitialization(t *testing.T) {
	intfBox = &dummyPlatformInterface{}
	fixtureDir := filepath.Join("..", "app", "src", "test", "resources", "config-v1.14")
	files, err := os.ReadDir(fixtureDir)
	if err != nil {
		t.Fatalf("Failed to read fixture directory: %v", err)
	}

	for _, entry := range files {
		fileName := entry.Name()
		if !strings.HasSuffix(fileName, ".json") {
			continue
		}

		t.Run(fileName, func(t *testing.T) {
			path := filepath.Join(fixtureDir, fileName)
			content, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("Failed to read fixture %s: %v", fileName, err)
			}

			var configJson string
			trimmed := strings.TrimSpace(string(content))
			if strings.HasPrefix(fileName, "full_") || fileName == "config_custom_direct.json" || fileName == "outbound_chain.json" {
				configJson = trimmed
			} else if strings.HasPrefix(fileName, "outbound_wireguard") {
				configJson = fmt.Sprintf(`{
					"log": {"level": "info"},
					"endpoints": [%s],
					"outbounds": [{"type": "direct", "tag": "direct"}]
				}`, trimmed)
			} else if strings.HasPrefix(fileName, "outbound_") {
				configJson = fmt.Sprintf(`{
					"log": {"level": "info"},
					"outbounds": [%s, {"type": "direct", "tag": "direct"}]
				}`, trimmed)
			} else {
				return
			}

			// Validate JSON syntax
			var parsed map[string]interface{}
			if err := json.Unmarshal([]byte(configJson), &parsed); err != nil {
				t.Fatalf("Invalid JSON in %s: %v", fileName, err)
			}

			instance, err := NewSingBoxInstance(configJson, nil)
			if err != nil {
				t.Fatalf("Failed to create sing-box 1.14 instance from %s: %v", fileName, err)
			}
			if instance == nil {
				t.Fatalf("Created nil instance from %s", fileName)
			}
			_ = instance.Close()
		})
	}
}
