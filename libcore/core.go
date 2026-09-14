package libcore

import (
	"errors"
	"github.com/killertop/Vialen/core/api"
)

// CoreImport parses a whole batch into typed profiles and explicit per-entry issues.
func CoreImport(input []byte) (output []byte, err error) {
	defer coreBoundary(&output, &err)
	return api.Import(input)
}
func CoreCompile(input []byte) (output []byte, err error) {
	defer coreBoundary(&output, &err)
	return api.Compile(input)
}
func CoreValidateProfiles(input []byte) (err error) {
	var output []byte
	defer coreBoundary(&output, &err)
	return api.ValidateProfiles(input)
}
func CoreValidateRuleMatch(input []byte) (err error) {
	var output []byte
	defer coreBoundary(&output, &err)
	return api.ValidateRuleMatch(input)
}
func coreBoundary(output *[]byte, err *error) {
	if recover() != nil {
		*output = nil
		*err = errors.New("core operation failed")
	}
}

func CoreExportProfile(input []byte) (output []byte, err error) {
	defer coreBoundary(&output, &err)
	return api.ExportProfile(input)
}
