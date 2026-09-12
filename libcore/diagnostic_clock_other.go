//go:build !android

package libcore

import "time"

func diagnosticNow() (time.Time, error) { return time.Now(), nil }
