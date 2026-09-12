// core-host runs the same pure Go boundary as Android for JVM contract tests.
// It is a local test executable and is never included in the APK.
package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"io"
	"os"

	"github.com/killertop/Vialen/core/api"
)

const maxFrame = 128 * 1024 * 1024

func frame(r io.Reader) ([]byte, error) {
	var size uint32
	if err := binary.Read(r, binary.BigEndian, &size); err != nil {
		return nil, err
	}
	if size > maxFrame {
		return nil, errors.New("host test frame exceeds limit")
	}
	data := make([]byte, size)
	_, err := io.ReadFull(r, data)
	return data, err
}
func run() error {
	in := bufio.NewReader(os.Stdin)
	out := bufio.NewWriter(os.Stdout)
	for {
		op, err := frame(in)
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		input, err := frame(in)
		if err != nil {
			return err
		}
		result, err := api.Execute(string(op), input)
		status := byte(0)
		if err != nil {
			status = 1
			result = []byte(err.Error())
		}
		if err = out.WriteByte(status); err != nil {
			return err
		}
		if err = binary.Write(out, binary.BigEndian, uint32(len(result))); err != nil {
			return err
		}
		if _, err = out.Write(result); err != nil {
			return err
		}
		if err = out.Flush(); err != nil {
			return err
		}
	}
}
func main() {
	if err := run(); err != nil {
		_, _ = os.Stderr.WriteString(err.Error() + "\n")
		os.Exit(1)
	}
}
