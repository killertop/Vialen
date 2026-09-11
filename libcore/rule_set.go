package libcore

import (
	"encoding/json"
	"fmt"
	"github.com/sagernet/sing-box/common/srs"
	"github.com/sagernet/sing-box/option"
	"io"
	"os"
)

// ValidateRuleSet validates imported files with the same decoder as the engine.
func ValidateRuleSet(path, format string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	switch format {
	case "binary":
		_, err = srs.Read(f, true)
		return err
	case "source":
		var set option.PlainRuleSetCompat
		decoder := json.NewDecoder(f)
		if err = decoder.Decode(&set); err != nil {
			return err
		}
		var extra any
		if err = decoder.Decode(&extra); err != io.EOF {
			return fmt.Errorf("trailing data in rule set")
		}
		return nil
	default:
		return fmt.Errorf("unsupported rule-set format: %s", format)
	}
}
