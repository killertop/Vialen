package importer

import (
	"encoding/json"
	"go.yaml.in/yaml/v3"
	"io"
	"math"
	"strconv"
	"strings"
)

func parseJSON(text string) (any, error) {
	if !validJSONStringUnicode(text) {
		return nil, bad("INVALID_JSON", "JSON contains an unpaired Unicode surrogate")
	}
	d := json.NewDecoder(strings.NewReader(text))
	d.UseNumber()
	value, e := jsonValue(d, 0)
	if e != nil {
		return nil, bad("INVALID_JSON", "Expected standard JSON with unique object keys and depth at most 64")
	}
	if _, e = d.Token(); e != io.EOF {
		return nil, bad("INVALID_JSON", "Unexpected trailing JSON content")
	}
	return value, nil
}
func jsonValue(d *json.Decoder, depth int) (any, error) {
	token, e := d.Token()
	if e != nil {
		return nil, e
	}
	if delim, ok := token.(json.Delim); ok {
		if depth >= MaxDepth {
			return nil, bad("EXCESSIVE_DEPTH", "JSON exceeds depth 64")
		}
		switch delim {
		case '{':
			m := map[string]any{}
			for d.More() {
				k, e := d.Token()
				if e != nil {
					return nil, e
				}
				s, ok := k.(string)
				if !ok {
					return nil, fieldError("object key")
				}
				if _, exists := m[s]; exists {
					return nil, fieldError("duplicate object key")
				}
				v, e := jsonValue(d, depth+1)
				if e != nil {
					return nil, e
				}
				m[s] = v
			}
			_, e = d.Token()
			return m, e
		case '[':
			a := []any{}
			for d.More() {
				v, e := jsonValue(d, depth+1)
				if e != nil {
					return nil, e
				}
				a = append(a, v)
			}
			_, e = d.Token()
			return a, e
		default:
			return nil, fieldError("JSON delimiter")
		}
	}
	return token, nil
}

const maxYAMLNodes = 1000000
const maxYAMLExpandedBytes = 32 << 20

type yamlBudget struct {
	nodes, bytes int
	active       map[*yaml.Node]bool
}

func parseYAML(text string) (any, error) {
	d := yaml.NewDecoder(strings.NewReader(text))
	var root yaml.Node
	if e := d.Decode(&root); e != nil {
		return nil, bad("INVALID_YAML", "Malformed YAML document")
	}
	var next yaml.Node
	if e := d.Decode(&next); e != io.EOF {
		return nil, bad("INVALID_YAML", "Only one YAML document is supported")
	}
	budget := yamlBudget{active: map[*yaml.Node]bool{}}
	v, e := budget.value(&root, 0)
	if e != nil {
		return nil, e
	}
	return v, nil
}
func (b *yamlBudget) value(n *yaml.Node, depth int) (any, error) {
	b.nodes++
	b.bytes += len(n.Value)
	if b.nodes > maxYAMLNodes || b.bytes > maxYAMLExpandedBytes || depth > MaxDepth || b.active[n] {
		return nil, bad("YAML_LIMIT", "YAML depth, alias, or expansion limit exceeded")
	}
	b.active[n] = true
	defer delete(b.active, n)
	switch n.Kind {
	case yaml.DocumentNode:
		if len(n.Content) != 1 {
			return nil, bad("INVALID_YAML", "Expected one document")
		}
		return b.value(n.Content[0], depth)
	case yaml.AliasNode:
		return b.value(n.Alias, depth+1)
	case yaml.SequenceNode:
		a := []any{}
		for _, c := range n.Content {
			v, e := b.value(c, depth+1)
			if e != nil {
				return nil, e
			}
			a = append(a, v)
		}
		return a, nil
	case yaml.MappingNode:
		m := map[string]any{}
		explicit := map[string]bool{}
		for i := 0; i < len(n.Content); i += 2 {
			k := n.Content[i]
			if k.Tag == "!!merge" {
				continue
			}
			if k.Kind != yaml.ScalarNode || k.Tag != "!!str" {
				return nil, bad("INVALID_YAML", "Mapping keys must be strings")
			}
			if explicit[k.Value] {
				return nil, bad("INVALID_YAML", "Duplicate mapping key")
			}
			explicit[k.Value] = true
			v, e := b.value(n.Content[i+1], depth+1)
			if e != nil {
				return nil, e
			}
			m[k.Value] = v
		}
		merge := func(v any) error {
			o, ok := v.(map[string]any)
			if !ok {
				return bad("INVALID_YAML", "Merge target must be a mapping")
			}
			for k, v := range o {
				if _, ok := m[k]; !ok {
					m[k] = v
				}
			}
			return nil
		}
		merges := 0
		for i := 0; i < len(n.Content); i += 2 {
			if n.Content[i].Tag != "!!merge" {
				continue
			}
			merges++
			if merges > 1 {
				return nil, bad("INVALID_YAML", "Duplicate merge key")
			}
			v, e := b.value(n.Content[i+1], depth+1)
			if e != nil {
				return nil, e
			}
			if a, ok := v.([]any); ok {
				for _, v := range a {
					if e = merge(v); e != nil {
						return nil, e
					}
				}
			} else if e = merge(v); e != nil {
				return nil, e
			}
		}
		return m, nil
	case yaml.ScalarNode:
		s := n.Value
		switch n.Tag {
		case "!!str":
			return s, nil
		case "!!null":
			return nil, nil
		case "!!bool":
			v, e := strconv.ParseBool(strings.ToLower(s))
			if e != nil {
				return nil, fieldError("YAML boolean")
			}
			return v, nil
		case "!!int":
			s = strings.ReplaceAll(s, "_", "")
			base := 10
			unsigned := strings.TrimPrefix(strings.TrimPrefix(s, "+"), "-")
			if strings.HasPrefix(unsigned, "0o") || strings.HasPrefix(unsigned, "0x") || strings.HasPrefix(unsigned, "0b") {
				base = 0
			}
			n, e := strconv.ParseInt(s, base, 64)
			if e != nil {
				return nil, fieldError("YAML integer")
			}
			return json.Number(strconv.FormatInt(n, 10)), nil
		case "!!float":
			v, e := strconv.ParseFloat(strings.ReplaceAll(s, "_", ""), 64)
			if e != nil || math.IsNaN(v) || math.IsInf(v, 0) {
				return nil, fieldError("YAML number")
			}
			return json.Number(strconv.FormatFloat(v, 'g', -1, 64)), nil
		default:
			return nil, bad("UNSUPPORTED_YAML_TAG", "Only standard JSON-compatible YAML scalars are supported")
		}
	}
	return nil, bad("INVALID_YAML", "Unsupported YAML node")
}
func object(v any) (map[string]any, error) {
	m, ok := v.(map[string]any)
	if !ok {
		return nil, bad("INVALID_ENTRY", "Expected an object")
	}
	return m, nil
}

// fields records the first type error, so mappings cannot silently coerce values.
type fields struct {
	m   map[string]any
	err error
}

func (f *fields) str(k string) string {
	v, ok := f.m[k]
	if !ok {
		return ""
	}
	s, ok := v.(string)
	if !ok && f.err == nil {
		f.err = fieldError(k)
	}
	return s
}
func (f *fields) boolean(k string) bool {
	v, ok := f.m[k]
	if !ok {
		return false
	}
	b, ok := v.(bool)
	if !ok && f.err == nil {
		f.err = fieldError(k)
	}
	return b
}
func (f *fields) uint(k string, max uint64) uint64 {
	v, ok := f.m[k]
	if !ok {
		return 0
	}
	n, ok := v.(json.Number)
	if !ok {
		if f.err == nil {
			f.err = fieldError(k)
		}
		return 0
	}
	u, e := strconv.ParseUint(n.String(), 10, 64)
	if e != nil || u > max {
		if f.err == nil {
			f.err = fieldError(k)
		}
		return 0
	}
	return u
}
func (f *fields) strings(k string) []string {
	v, ok := f.m[k]
	if !ok {
		return nil
	}
	a, ok := v.([]any)
	if !ok {
		if f.err == nil {
			f.err = fieldError(k)
		}
		return nil
	}
	out := make([]string, 0, len(a))
	for _, v := range a {
		s, ok := v.(string)
		if !ok {
			if f.err == nil {
				f.err = fieldError(k)
			}
			return nil
		}
		out = append(out, s)
	}
	return out
}
func (f *fields) child(k string) *fields {
	v, exists := f.m[k]
	if !exists {
		return &fields{m: map[string]any{}}
	}
	m, e := object(v)
	if e != nil && f.err == nil {
		f.err = fieldError(k)
	}
	return &fields{m: m, err: e}
}
func (f *fields) accept(child *fields) {
	if f.err == nil {
		f.err = child.err
	}
}
func (f *fields) has(k string) bool { _, ok := f.m[k]; return ok }

// Go replaces lone escaped surrogates with U+FFFD; reject instead of altering a secret.
func validJSONStringUnicode(s string) bool {
	inString := false
	hex := func(s string) (uint64, error) { return strconv.ParseUint(s, 16, 16) }
	for i := 0; i < len(s); i++ {
		if s[i] == '"' {
			inString = !inString
			continue
		}
		if !inString || s[i] != '\\' {
			continue
		}
		if i+1 >= len(s) {
			return false
		}
		if s[i+1] != 'u' {
			i++
			continue
		}
		if i+6 > len(s) {
			return false
		}
		u, e := hex(s[i+2 : i+6])
		if e != nil {
			return false
		}
		if u >= 0xdc00 && u <= 0xdfff {
			return false
		}
		if u >= 0xd800 && u <= 0xdbff {
			if i+12 > len(s) || s[i+6:i+8] != "\\u" {
				return false
			}
			lo, e := hex(s[i+8 : i+12])
			if e != nil || lo < 0xdc00 || lo > 0xdfff {
				return false
			}
			i += 11
		} else {
			i += 5
		}
	}
	return true
}
