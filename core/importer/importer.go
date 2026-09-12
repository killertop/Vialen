// Package importer converts explicit third-party formats to validated profiles.
// It performs no networking, platform calls, or persistence.
package importer

import (
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/killertop/Vialen/core/profile"
)

const MaxInputBytes = 16 << 20
const MaxProfiles = 10000
const MaxDepth = 64

type Request struct {
	Text     string `json:"text"`
	Format   string `json:"format,omitempty"`
	FileName string `json:"file_name,omitempty"`
}

const SeverityWarning = "warning"
const SeverityError = "error"

type Issue struct {
	Index    int    `json:"index"`
	Code     string `json:"code"`
	Message  string `json:"message"`
	Severity string `json:"severity"`
}
type Result struct {
	Profiles []profile.Profile `json:"profiles"`
	Issues   []Issue           `json:"issues"`
	Format   string            `json:"format"`
}

// HasErrors lets refresh workflows fail closed while retaining visible warnings.
func (r Result) HasErrors() bool {
	for _, issue := range r.Issues {
		if issue.Severity != SeverityWarning {
			return true
		}
	}
	return false
}

// Error identifies a document-level failure. Its message never includes input.
type Error struct{ Code, Message string }

func (e *Error) Error() string       { return e.Code + ": " + e.Message }
func bad(code, message string) error { return &Error{code, message} }
func result(format string) Result {
	return Result{Profiles: []profile.Profile{}, Issues: []Issue{}, Format: format}
}
func (r *Result) add(index int, p profile.Profile, err error) {
	if p.Shadowsocks != nil {
		options := *p.Shadowsocks
		options.Plugin = profile.NormalizePlugin(options.Plugin)
		p.Shadowsocks = &options
	}
	if err == nil {
		err = profile.Validate(p)
	}
	if err != nil {
		code, message := "INVALID_PROFILE", "Profile validation failed"
		var known *Error
		if errors.As(err, &known) {
			code, message = known.Code, known.Message
		} else {
			var invalid *profile.Error
			if errors.As(err, &invalid) {
				message = "Invalid " + invalid.Field + " (" + invalid.Code + ")"
			}
		}
		severity := SeverityError
		if code == "NON_PROXY_ENTRY" {
			severity = SeverityWarning
		}
		r.Issues = append(r.Issues, Issue{Index: index, Code: code, Message: message, Severity: severity})
		return
	}
	r.Profiles = append(r.Profiles, p)
}

// Parse returns per-entry Issues for a partially importable batch. Callers must
// show them before committing the returned Profiles; a nil error is not a claim
// that every source entry was accepted. Document failures never trigger another
// format parser. Index is the zero-based source-entry index (line for links).
func Parse(req Request) (Result, error) {
	format := strings.ToLower(strings.TrimSpace(req.Format))
	if format == "" {
		format = "auto"
	}
	out := result(format)
	if len(req.Text) > MaxInputBytes {
		return out, bad("INPUT_TOO_LARGE", "Subscription exceeds 16 MiB")
	}
	if !utf8.ValidString(req.Text) {
		return out, bad("INVALID_UTF8", "Subscription must be UTF-8")
	}
	text := strings.TrimSpace(req.Text)
	if text == "" {
		return out, bad("EMPTY_INPUT", "Subscription is empty")
	}
	if format == "auto" {
		format = detect(text)
	}
	out.Format = format
	switch format {
	case "links":
		return parseLinks(text)
	case "base64":
		decoded, err := decodeBase64(text)
		if err != nil {
			return out, err
		}
		if len(decoded) > MaxInputBytes {
			return out, bad("INPUT_TOO_LARGE", "Decoded subscription exceeds 16 MiB")
		}
		if !utf8.Valid(decoded) {
			return out, bad("INVALID_UTF8", "Decoded subscription must be UTF-8")
		}
		r, e := parseLinks(strings.TrimSpace(string(decoded)))
		r.Format = "base64"
		return r, e
	case "profiles":
		return parseProfiles(req.Text)
	case "clash":
		return parseClash(text)
	case "singbox":
		return parseSingbox(text)
	case "wireguard":
		return parseWireGuard(text, req.FileName)
	default:
		return out, bad("UNSUPPORTED_FORMAT", "Choose auto, links, base64, clash, singbox, profiles, or wireguard")
	}
}
func detect(text string) string {
	first := ""
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		first = strings.TrimSpace(strings.SplitN(line, "#", 2)[0])
		break
	}
	if first == "[Interface]" || strings.HasPrefix(text, "#") && strings.Contains(text, "\n[Interface]") {
		return "wireguard"
	}
	if strings.HasPrefix(text, "{") || strings.HasPrefix(text, "[") {
		return "singbox"
	}
	if strings.HasPrefix(text, "---") || strings.HasPrefix(text, "proxies:") || strings.Contains(text, "\nproxies:") {
		return "clash"
	}
	if !strings.Contains(text, "://") && base64Alphabet(text) {
		return "base64"
	}
	return "links"
}
func base64Alphabet(s string) bool {
	count := 0
	for _, c := range s {
		if c == ' ' || c == '\r' || c == '\n' || c == '\t' {
			continue
		}
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune("+/-_=", c)) {
			return false
		}
		count++
	}
	return count >= 8
}
func decodeBase64(s string) ([]byte, error) {
	clean := strings.Map(func(r rune) rune {
		if r == ' ' || r == '\r' || r == '\n' || r == '\t' {
			return -1
		}
		return r
	}, s)
	b, e := profile.DecodeBase64(clean)
	if e != nil {
		return nil, bad("INVALID_BASE64", "Invalid Base64 subscription")
	}

	return b, nil
}
func parseLinks(text string) (Result, error) {
	out := result("links")
	lines := strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n")
	count := 0
	for _, line := range lines {
		if s := strings.TrimSpace(line); s != "" && !strings.HasPrefix(s, "#") {
			count++
		}
	}
	if count > MaxProfiles {
		return out, bad("TOO_MANY_PROFILES", "Subscription exceeds 10000 entries")
	}
	for index, line := range lines {
		s := strings.TrimSpace(line)
		if s == "" || strings.HasPrefix(s, "#") {
			continue
		}
		if strings.HasPrefix(s, "sn://") {
			out.add(index, profile.Profile{}, bad("UNSUPPORTED_SCHEME", "Universal binary profiles are not supported"))
			continue
		}
		p, e := profile.ParseURI(s)
		out.add(index, p, e)
	}
	if count == 0 {
		return out, bad("EMPTY_INPUT", "Subscription contains no links")
	}
	return out, nil
}
func sourceLimit(n int) error {
	if n > MaxProfiles {
		return bad("TOO_MANY_PROFILES", "Subscription exceeds 10000 entries")
	}
	return nil
}
func fieldError(name string) error {
	return bad("INVALID_FIELD", fmt.Sprintf("Invalid field %s", name))
}
