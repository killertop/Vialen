//go:build tools

package libcore

// Keep gobind's generator dependency in the module graph. The pinned external
// gomobile tool uses it, so go mod tidy cannot infer it from production imports.
import _ "golang.org/x/mobile/bind"
