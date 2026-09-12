package importer

import (
	"bytes"
	"encoding/json"
	"github.com/killertop/Vialen/core/profile"
)

// Native sharing uses the same typed profile fields as persistence and compilation.
// It never serializes an Android form or its implementation-specific binary layout.
func parseProfiles(text string) (Result, error) {
	value, err := parseJSON(text)
	if err != nil {
		return result("profiles"), err
	}
	root, ok := value.(map[string]any)
	if !ok {
		return result("profiles"), bad("INVALID_PROFILES", "Expected a profiles document")
	}
	return parseProfilesValue(root)
}

func parseProfilesValue(root map[string]any) (Result, error) {
	out := result("profiles")
	if len(root) != 1 {
		return out, bad("INVALID_PROFILES", "Only profiles is allowed in a profiles document")
	}
	entries, ok := root["profiles"].([]any)
	if !ok {
		return out, bad("INVALID_PROFILES", "profiles must be an array")
	}
	if err := sourceLimit(len(entries)); err != nil {
		return out, err
	}
	for index, entry := range entries {
		encoded, err := json.Marshal(entry)
		var p profile.Profile
		if err == nil {
			decoder := json.NewDecoder(bytes.NewReader(encoded))
			decoder.DisallowUnknownFields()
			err = decoder.Decode(&p)
		}
		if err != nil {
			out.add(index, p, bad("INVALID_PROFILE", "Unknown field or invalid profile field type"))
			continue
		}
		out.add(index, p, nil)
	}
	return out, nil
}
