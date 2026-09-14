// Package api is the small JSON transport boundary used by gomobile.
// Typed domain packages contain all business logic and have no JNI dependency.
package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"unicode/utf8"

	"github.com/killertop/Vialen/core/compiler"
	"github.com/killertop/Vialen/core/importer"
	"github.com/killertop/Vialen/core/profile"
)

// The transport limit applies after JSON encoding; import content also has its own limit.
const MaxRequestBytes = 20 * 1024 * 1024

func decode(input []byte, target any) error {
	if len(input) > MaxRequestBytes {
		return errors.New("request exceeds size limit")
	}
	if !utf8.Valid(input) {
		return errors.New("request is not valid UTF-8")
	}
	dec := json.NewDecoder(bytes.NewReader(input))
	dec.DisallowUnknownFields()
	if err := dec.Decode(target); err != nil {
		return err
	}
	var trailing any
	if err := dec.Decode(&trailing); err != io.EOF {
		return errors.New("request contains trailing data")
	}
	return nil
}

func Import(input []byte) ([]byte, error) {
	var request importer.Request
	if err := decode(input, &request); err != nil {
		return nil, err
	}
	result, err := importer.Parse(request)
	if err != nil {
		return nil, err
	}
	return json.Marshal(result)
}
func Compile(input []byte) ([]byte, error) {
	var request compiler.Request
	if err := decode(input, &request); err != nil {
		return nil, err
	}
	result, err := compiler.Compile(request)
	if err != nil {
		return nil, err
	}
	return json.Marshal(result)
}
func ValidateProfiles(input []byte) error {
	var request struct {
		Profiles []profile.Profile `json:"profiles"`
	}
	if err := decode(input, &request); err != nil {
		return err
	}
	if len(request.Profiles) == 0 || len(request.Profiles) > importer.MaxProfiles {
		return errors.New("profile count must be 1..10000")
	}
	for _, p := range request.Profiles {
		if err := profile.Validate(p); err != nil {
			return err
		}
	}
	return nil
}

func ValidateRuleMatch(input []byte) error {
	var request struct {
		Match    *compiler.Match    `json:"match"`
		RuleSets []compiler.RuleSet `json:"rule_sets"`
	}
	if err := decode(input, &request); err != nil {
		return err
	}
	return compiler.ValidateRuleWithSets(request.Match, request.RuleSets)
}

func ExportProfile(input []byte) ([]byte, error) {
	var p profile.Profile
	if err := decode(input, &p); err != nil {
		return nil, err
	}
	uri, err := profile.ExportURI(p)
	if err != nil {
		return nil, err
	}
	return json.Marshal(struct {
		URI string `json:"uri"`
	}{uri})
}

// Execute is shared by the Android binding and the local contract-test host.
func Execute(operation string, input []byte) ([]byte, error) {
	switch operation {
	case "import":
		return Import(input)
	case "compile":
		return Compile(input)
	case "export":
		return ExportProfile(input)
	case "validate":
		return []byte("{}"), ValidateProfiles(input)
	case "validate_rule":
		return []byte("{}"), ValidateRuleMatch(input)
	default:
		return nil, errors.New("unknown core operation")
	}
}
